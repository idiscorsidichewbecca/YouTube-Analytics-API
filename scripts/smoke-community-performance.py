import base64
import json
import os
import socket
import subprocess
import tempfile
import time
from pathlib import Path
import re
from urllib.request import Request, build_opener, ProxyHandler

# Run after Maven verify with Java 21 on PATH (or set JAVA_HOME).
java = str(Path(os.environ['JAVA_HOME']) / 'bin' / 'java') if os.environ.get('JAVA_HOME') else 'java'
with tempfile.TemporaryDirectory() as tmp:
    key = Path(tmp) / 'key.pem'
    subprocess.run(['openssl', 'genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:3072', '-out', str(key)],
                   stderr=subprocess.DEVNULL, check=True)
    der = subprocess.check_output(['openssl', 'pkcs8', '-topk8', '-nocrypt', '-in', str(key), '-outform', 'DER'])
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        port = sock.getsockname()[1]
    url = 'http://127.0.0.1:' + str(port)
    issuer = 'https://localhost:' + str(port)
    env = dict(os.environ, GOOGLE_CLIENT_ID='local-smoke-client', GOOGLE_CLIENT_SECRET='local-smoke-secret',
               YOUTUBE_EXPECTED_CHANNEL_ID='test-channel', GOOGLE_LOGIN_HINT='',
               ADMIN_USERNAME='smoke', ADMIN_PASSWORD='local-test-password-only', GPT_ACTION_API_KEY='local-smoke-key',
               TOKEN_ENCRYPTION_KEY=base64.b64encode(os.urandom(32)).decode(),
               SPRING_DATASOURCE_URL='jdbc:h2:mem:chewbecca_smoke;DB_CLOSE_DELAY=-1', MCP_ENABLED='true',
               APP_INSTAGRAM_ENABLED='true', APP_COMMUNITY_ENABLED='true', APP_PERFORMANCE_ENABLED='true',
               APP_INSTAGRAM_LIVE_ENABLED='false', APP_PERFORMANCE_REPORTING_ENABLED='false',
               APP_COMMUNITY_DRY_RUN='true', APP_PERFORMANCE_DRY_RUN='true', MCP_PUBLIC_URL=issuer, MCP_SIGNING_PRIVATE_KEY_PKCS8_B64=base64.b64encode(der).decode(), PORT=str(port))
    compact = lambda x: base64.urlsafe_b64encode(x).rstrip(b'=')
    def make_token(scope):
        message = compact(b'{"alg":"RS256"}') + b'.' + compact(json.dumps({
            'sub': 'smoke', 'scope': scope, 'iss': issuer, 'aud': [issuer+'/mcp'],
            'iat': int(time.time()), 'exp': int(time.time())+120}).encode())
        signature = subprocess.check_output(['openssl', 'dgst', '-sha256', '-sign', str(key)], input=message)
        return (message+b'.'+compact(signature)).decode()
    opener = build_opener(ProxyHandler({}))

    def rpc(method, params=None, scope='shorts:read shorts:write'):
        body = dict(jsonrpc='2.0', id=1, method=method)
        if params is not None:
            body['params'] = params
        request = Request(url+'/mcp', data=json.dumps(body).encode(),
                          headers={'Content-Type':'application/json', 'Accept':'application/json, text/event-stream',
                                   'Authorization':'Bearer '+make_token(scope), 'MCP-Protocol-Version':'2025-11-25'})
        with opener.open(request, timeout=8) as response:
            raw = response.read().decode()
        return json.loads(next((line[6:] for line in raw.splitlines() if line.startswith('data: ')), raw))

    logpath = Path(tmp) / 'application.log'
    with logpath.open('wb') as output:
        process = subprocess.Popen([java, '-jar',
                                    'target/youtube-analytics-api-0.0.1-SNAPSHOT.jar',
                                    '--server.address=127.0.0.1'], env=env, stdout=output, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 35
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise SystemExit(logpath.read_text()[-3500:])
                try:
                    with opener.open(url+'/actuator/health', timeout=2) as response:
                        assert json.load(response)['status'] == 'UP'
                    break
                except OSError:
                    time.sleep(0.5)
            else:
                raise SystemExit(logpath.read_text()[-3500:])
            tools = {tool['name']: tool for tool in rpc('tools/list')['result']['tools']}
            assert len(tools) == 41, list(tools)
            files = Path('src/main/java/it/chewbecca/youtubeanalytics/mcp')
            legacy_names = set()
            for name in ['ChewbeccaMcpTools', 'ChewbeccaMcpPublishingTools', 'ChewbeccaMcpWeeklyTools',
                         'ChewbeccaMcpThumbnailTools', 'ChewbeccaMcpQuizTools']:
                legacy_names.update(re.findall(r'"(chewbecca_[a-z_]+)"', (files / (name + '.java')).read_text()))
            assert len(legacy_names) == 21, legacy_names
            assert legacy_names.issubset(tools), legacy_names.difference(tools)
            assert tools['chewbecca_prepare_video_optimization']['_meta']['openai/fileParams'] == ['option_a', 'option_b', 'option_c']
            for name in ['chewbecca_publish_comment_replies', 'chewbecca_apply_video_optimization']:
                spec = tools[name]
                assert spec['annotations']['destructiveHint']
                response = rpc('tools/call', {'name': name, 'arguments': {}}, 'shorts:read')
                assert response['result']['isError'], response
                assert 'mcp/www_authenticate' in response['result']['_meta'], response
            log = logpath.read_text().lower()
            assert 'community manager' in log and 'performance advisor' in log, log[-5000:]
            print('Health UP, Flyway V9/V10, 21 strumenti invariati, 20 nuovi strumenti e scope di scrittura verificati.')
        finally:
            process.terminate()
            try:
                process.wait(timeout=4)
            except subprocess.TimeoutExpired:
                process.kill()
