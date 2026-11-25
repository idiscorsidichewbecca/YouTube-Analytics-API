import io
from PIL import Image
from fastapi.testclient import TestClient
import app as service

TOKEN = 'x' * 40
HEAD = {'Authorization': 'Bearer ' + TOKEN}


def asset():
    b = io.BytesIO()
    Image.new('RGB', (360, 640)).save(b, 'PNG')
    return ('story.png', b.getvalue(), 'image/png')


def prepare(c, **fields):
    return c.post('/stories/drafts', headers=HEAD, files={'image': asset()}, data=fields)


def test_flow(tmp_path, monkeypatch):
    monkeypatch.setenv('DATA_DIR', str(tmp_path))
    monkeypatch.setenv('SERVICE_TOKEN', TOKEN)
    monkeypatch.setenv('ENABLE_LIVE', 'false')
    c = TestClient(service.app)
    assert c.get('/stories/drafts/00000000-0000-0000-0000-000000000000').status_code == 401
    assert prepare(c, kind='LINK', link='http://youtube.com').status_code == 422
    assert prepare(c, kind='POLL', question='Question?', options_json='["A","A"]').status_code == 422
    p = prepare(c, kind='LINK', link='https://youtu.be/example').json()
    url = '/stories/drafts/' + p['draftId'] + '/publish'
    assert c.post(url, headers=HEAD, json={'confirmationText': 'no'}).status_code == 403
    confirm = {'confirmationText': 'CONFERMO STORY ' + p['draftId']}
    assert c.post(url, headers=HEAD, json=confirm).json()['status'] == 'DRY_RUN'
    monkeypatch.setenv('ENABLE_LIVE', 'true')
    assert c.post(url, headers=HEAD, json=confirm).status_code == 503
    (tmp_path / 'session.json').write_text('{}')
    calls = []
    monkeypatch.setattr(service, 'publish_to_instagram', lambda path, payload: calls.append(payload) or '123')
    assert c.post(url, headers=HEAD, json=confirm).json()['storyId'] == '123'
    assert c.post(url, headers=HEAD, json=confirm).json()['status'] == 'PUBLISHED'
    assert len(calls) == 1


def test_unknown_no_retry(tmp_path, monkeypatch):
    monkeypatch.setenv('DATA_DIR', str(tmp_path))
    monkeypatch.setenv('SERVICE_TOKEN', TOKEN)
    monkeypatch.setenv('ENABLE_LIVE', 'true')
    (tmp_path / 'session.json').write_text('{}')
    c = TestClient(service.app)
    p = prepare(c, kind='POLL', question='Question?', options_json='["A","B"]').json()
    calls = []
    def fail(*args):
        calls.append(1)
        raise TimeoutError()
    monkeypatch.setattr(service, 'publish_to_instagram', fail)
    for _ in range(2):
        r = c.post('/stories/drafts/' + p['draftId'] + '/publish', headers=HEAD, json={'confirmationText': 'CONFERMO STORY ' + p['draftId']})
        assert r.json()['status'] == 'UNKNOWN'
    assert len(calls) == 1


def test_scheduler_and_cancel(tmp_path, monkeypatch):
    from datetime import datetime, timezone, timedelta
    monkeypatch.setenv('DATA_DIR', str(tmp_path))
    monkeypatch.setenv('SERVICE_TOKEN', TOKEN)
    monkeypatch.setenv('ENABLE_LIVE', 'false')
    c = TestClient(service.app)
    draft = prepare(c, kind='PLAIN').json()['draftId']
    url = '/stories/drafts/' + draft
    at = (datetime.now(timezone.utc) + timedelta(minutes=5)).replace(microsecond=0)
    body = {'confirmationText': f'CONFERMO STORY {draft} {at.isoformat()}', 'publishAt': at.isoformat()}
    assert c.post(url + '/schedule', headers=HEAD, json=body).json()['status'] == 'DRY_RUN'
    assert c.get(url, headers=HEAD).json()['status'] == 'DRAFT'
    monkeypatch.setenv('ENABLE_LIVE', 'true')
    (tmp_path / 'session.json').write_text('{}')
    assert c.post(url + '/schedule', headers=HEAD, json={**body,'confirmationText':'no'}).status_code == 403
    assert c.post(url + '/schedule', headers=HEAD, json=body).json()['status'] == 'SCHEDULED'
    calls=[]
    monkeypatch.setattr(service, 'publish_to_instagram', lambda path,p: calls.append(p) or '456')
    service.process_due(at.timestamp()-1)
    assert not calls
    service.process_due(at.timestamp()+1)
    service.process_due(at.timestamp()+2)
    assert len(calls) == 1
    assert c.get(url, headers=HEAD).json()['status'] == 'PUBLISHED'
    draft2=prepare(c, kind='PLAIN').json()['draftId']
    url2='/stories/drafts/'+draft2
    assert c.post(url2+'/schedule', headers=HEAD, json={**body,'confirmationText': f'CONFERMO STORY {draft2} {at.isoformat()}'}).json()['status']=='SCHEDULED'
    assert c.post(url2+'/cancel', headers=HEAD, json={'confirmationText':f'ANNULLA STORY {draft2}'}).json()['status']=='CANCELLED'
    service.process_due(at.timestamp()+3)
    assert len(calls)==1


def test_stale_schedule_is_missed(tmp_path, monkeypatch):
    from datetime import datetime, timezone, timedelta
    monkeypatch.setenv('DATA_DIR', str(tmp_path))
    monkeypatch.setenv('SERVICE_TOKEN', TOKEN)
    monkeypatch.setenv('ENABLE_LIVE', 'true')
    (tmp_path / 'session.json').write_text('{}')
    c=TestClient(service.app)
    draft=prepare(c, kind='PLAIN').json()['draftId']
    at=(datetime.now(timezone.utc)+timedelta(minutes=5)).replace(microsecond=0)
    url='/stories/drafts/'+draft
    assert c.post(url+'/schedule',headers=HEAD,json={'publishAt':at.isoformat(),'confirmationText':f'CONFERMO STORY {draft} {at.isoformat()}'}).json()['status']=='SCHEDULED'
    def must_not_publish(*args):
        raise AssertionError('stale publication')
    monkeypatch.setattr(service,'publish_to_instagram',must_not_publish)
    service.process_due(at.timestamp()+901)
    assert c.get(url,headers=HEAD).json()['status']=='MISSED'


def test_duplicate_question_history(tmp_path, monkeypatch):
    monkeypatch.setenv('DATA_DIR', str(tmp_path))
    monkeypatch.setenv('SERVICE_TOKEN', TOKEN)
    c=TestClient(service.app)
    p=prepare(c,kind='POLL',question='Chi era Dooku?',options_json='["Jedi","Sith"]').json()
    assert prepare(c,kind='POLL',question='CHI ERA DOOKU!',options_json='["A","B"]').status_code==409
    assert c.get('/stories/quiz-history',headers=HEAD).json()['questions'][0]['draftId']==p['draftId']
    assert c.post('/stories/drafts/'+p['draftId']+'/cancel',headers=HEAD,json={'confirmationText':'ANNULLA STORY '+p['draftId']}).json()['status']=='CANCELLED'
    assert prepare(c,kind='POLL',question='Chi era Dooku?',options_json='["A","B"]').status_code==200
