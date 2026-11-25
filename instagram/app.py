import hashlib
import hmac
import io
import json
import os
import sqlite3
import threading
import uuid
import time
import unicodedata
import asyncio
from contextlib import asynccontextmanager, contextmanager
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlparse

from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, UploadFile
from PIL import Image, ImageOps, UnidentifiedImageError
from pydantic import BaseModel

@asynccontextmanager
async def lifespan(app):
    stop = threading.Event()
    worker = threading.Thread(target=run_scheduler, args=(stop,), daemon=True)
    worker.start()
    try:
        yield
    finally:
        stop.set()
        await asyncio.to_thread(worker.join, 10)

app = FastAPI(title='Chewbecca Instagram', docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)
LOCK = threading.Lock()
MAX_BYTES = 12 * 1024 * 1024


def data_dir():
    p = Path(os.getenv('DATA_DIR', './data'))
    p.mkdir(mode=0o700, parents=True, exist_ok=True)
    return p


@contextmanager
def db():
    c = sqlite3.connect(data_dir() / 'stories.sqlite3', timeout=15)
    c.row_factory = sqlite3.Row
    c.execute('CREATE TABLE IF NOT EXISTS draft (id TEXT PRIMARY KEY, payload TEXT NOT NULL, status TEXT NOT NULL, story_id TEXT, sticker_status TEXT)')
    try:
        c.execute('BEGIN IMMEDIATE')
        if 'publish_at' not in {r[1] for r in c.execute('PRAGMA table_info(draft)')}:
            c.execute('ALTER TABLE draft ADD COLUMN publish_at REAL')
        c.commit()
        with c:
            yield c
    finally:
        c.close()


def auth(authorization: str = Header(default='')):
    token = os.getenv('SERVICE_TOKEN', '')
    if len(token) < 32:
        raise HTTPException(503, 'Service token not configured')
    if not hmac.compare_digest(authorization, 'Bearer ' + token):
        raise HTTPException(401, 'Unauthorized')


def validate_payload(kind, link, question, options):
    if kind not in ('LINK', 'POLL', 'PLAIN'):
        raise HTTPException(422, 'kind must be LINK, POLL or PLAIN')
    if kind == 'LINK':
        u = urlparse(link)
        if u.scheme != 'https' or u.hostname not in ('youtube.com', 'www.youtube.com', 'm.youtube.com', 'youtu.be') or u.username or u.password or question or options:
            raise HTTPException(422, 'HTTPS YouTube link required; poll fields must be empty')
    elif kind == 'POLL':
        if link or not question.strip() or len(question) > 100 or not 2 <= len(options) <= 4 or any(not isinstance(o, str) or not o.strip() or len(o) > 40 for o in options) or len(set(o.strip().casefold() for o in options)) != len(options):
            raise HTTPException(422, 'Poll requires a question and 2-4 distinct nonempty options')
    elif link or question or options:
        raise HTTPException(422, 'Plain story cannot have sticker fields')
    return {'kind': kind, 'link': link, 'question': question, 'options': options}


def row_for(c, draft_id):
    r = c.execute('SELECT * FROM draft WHERE id=?', (str(draft_id),)).fetchone()
    if r is None:
        raise HTTPException(404, 'Draft not found')
    return r


def response(r):
    return {'draftId': r['id'], 'status': r['status'], 'storyId': r['story_id'], 'stickerStatus': r['sticker_status'], 'publishAt': datetime.fromtimestamp(r['publish_at'], timezone.utc).isoformat() if r['publish_at'] is not None else None, **json.loads(r['payload'])}


@app.get('/health')
def health():
    return {'status': 'UP', 'liveEnabled': os.getenv('ENABLE_LIVE', 'false').lower() == 'true'}


@app.post('/stories/drafts', dependencies=[Depends(auth)])
def prepare(image: UploadFile = File(...), kind: str = Form(...), link: str = Form(''), question: str = Form(''), options_json: str = Form('[]')):
    try:
        options = json.loads(options_json)
        if not isinstance(options, list):
            raise ValueError()
    except ValueError:
        raise HTTPException(422, 'options_json must be a JSON array')
    payload = validate_payload(kind, link, question, options)
    raw = image.file.read(MAX_BYTES + 1)
    if len(raw) > MAX_BYTES:
        raise HTTPException(413, 'Image too large')
    draft_id = str(uuid.uuid4())
    path = data_dir() / (draft_id + '.jpg')
    try:
        with Image.open(io.BytesIO(raw)) as im:
            if im.format not in ('JPEG', 'PNG') or im.width * im.height > 20_000_000:
                raise ValueError('Unsupported format or size')
            im = ImageOps.exif_transpose(im).convert('RGB')
            if abs(im.width / im.height - 9 / 16) > .015:
                raise ValueError('Expected 9:16 story asset')
            im.resize((1080, 1920), Image.Resampling.LANCZOS).save(path, 'JPEG', quality=92)
    except (UnidentifiedImageError, OSError, ValueError, Image.DecompressionBombError):
        path.unlink(missing_ok=True)
        raise HTTPException(422, 'Provide a valid 9:16 JPG or PNG')
    payload['imageSha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
    try:
        with db() as c:
            c.execute('BEGIN IMMEDIATE')
            if kind == 'POLL':
                existing = c.execute("SELECT payload FROM draft WHERE status NOT IN ('CANCELLED','MISSED','BLOCKED')").fetchall()
                if any(normalize_question(json.loads(row['payload']).get('question', '')) == normalize_question(question) for row in existing):
                    raise HTTPException(409, 'Question already used or prepared')
            c.execute('INSERT INTO draft (id,payload,status,story_id,sticker_status) VALUES (?, ?, ?, NULL, NULL)', (draft_id, json.dumps(payload), 'DRAFT'))
            return response(row_for(c, draft_id))
    except Exception:
        path.unlink(missing_ok=True)
        raise


def normalize_question(text):
    return ' '.join(''.join(ch if ch.isalnum() else ' ' for ch in unicodedata.normalize('NFKC', text).casefold()).split())


@app.get('/stories/quiz-history', dependencies=[Depends(auth)])
def quiz_history():
    with db() as c:
        rows = c.execute("SELECT * FROM draft WHERE status NOT IN ('CANCELLED','MISSED','BLOCKED') ORDER BY rowid DESC").fetchall()
        questions = [response(r) for r in rows if json.loads(r['payload'])['kind'] == 'POLL']
        return {'questions': [{k: q[k] for k in ('draftId','question','status','publishAt')} for q in questions]}


@app.get('/stories/drafts/{draft_id}', dependencies=[Depends(auth)])
def status(draft_id: uuid.UUID):
    with db() as c:
        return response(row_for(c, draft_id))


class Confirmation(BaseModel):
    confirmationText: str
    publishAt: datetime | None = None


def publish_to_instagram(path, payload):
    # No automatic re-login, no challenge bypass and no retries.
    from instagrapi import Client
    from instagrapi.types import StoryLink, StoryPoll
    session = data_dir() / 'session.json'
    if not session.is_file():
        raise RuntimeError('Session not configured')
    cl = Client()
    cl.load_settings(str(session))
    links, polls = [], []
    if payload['kind'] == 'LINK':
        links = [StoryLink(webUri=payload['link'], x=.5, y=.76, width=.55, height=.10)]
    elif payload['kind'] == 'POLL':
        polls = [StoryPoll(x=.5, y=.67, width=.78, height=.28, question=payload['question'], options=payload['options'])]
    story = cl.photo_upload_to_story(path, links=links, polls=polls)
    # Upload acknowledgement is not proof that the interactive sticker works.
    return str(story.pk)


def live_enabled():
    return os.getenv('ENABLE_LIVE', 'false').lower() == 'true'


def submit_locked(draft_id, allowed):
    # Caller holds LOCK; persist PUBLISHING before any network call.
    with db() as c:
        c.execute('BEGIN IMMEDIATE')
        r = row_for(c, draft_id)
        if r['status'] not in allowed:
            return response(r)
        if not live_enabled():
            return {**response(r), 'status': 'DRY_RUN', 'published': False}
        if not (data_dir() / 'session.json').is_file():
            raise HTTPException(503, 'Instagram session not configured')
        p = json.loads(r['payload'])
        path = data_dir() / (str(draft_id) + '.jpg')
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != p['imageSha256']:
            raise HTTPException(409, 'Draft asset missing or modified')
        c.execute('UPDATE draft SET status=? WHERE id=?', ('PUBLISHING', str(draft_id)))
    try:
        story_id = publish_to_instagram(path, p)
        state, sticker = 'PUBLISHED', 'MANUAL_CHECK_REQUIRED' if p['kind'] != 'PLAIN' else 'NOT_APPLICABLE'
    except Exception:
        story_id, state, sticker = None, 'UNKNOWN', 'MANUAL_CHECK_REQUIRED'
    with db() as c:
        c.execute('UPDATE draft SET status=?, story_id=?, sticker_status=? WHERE id=?', (state, story_id, sticker, str(draft_id)))
        return response(row_for(c, draft_id))


@app.post('/stories/drafts/{draft_id}/publish', dependencies=[Depends(auth)])
def publish(draft_id: uuid.UUID, body: Confirmation):
    if body.confirmationText != f'CONFERMO STORY {draft_id}':
        raise HTTPException(403, 'Explicit draft confirmation required')
    if body.publishAt is not None:
        raise HTTPException(422, 'Use /schedule for a future date')
    with LOCK:
        return submit_locked(draft_id, ('DRAFT',))


@app.post('/stories/drafts/{draft_id}/schedule', dependencies=[Depends(auth)])
def schedule(draft_id: uuid.UUID, body: Confirmation):
    if body.publishAt is None or body.publishAt.utcoffset() is None:
        raise HTTPException(422, 'ISO 8601 date with timezone required')
    canonical = body.publishAt.isoformat()
    if body.confirmationText != f'CONFERMO STORY {draft_id} {canonical}':
        raise HTTPException(403, 'Confirm exact draft and date')
    when = body.publishAt.timestamp()
    if not time.time() + 60 <= when <= time.time() + 90 * 86400:
        raise HTTPException(422, 'Schedule between 1 minute and 90 days ahead')
    with LOCK, db() as c:
        c.execute('BEGIN IMMEDIATE')
        r = row_for(c, draft_id)
        if r['status'] != 'DRAFT':
            return response(r)
        if not live_enabled():
            return {**response(r), 'status': 'DRY_RUN', 'published': False}
        if not (data_dir() / 'session.json').is_file():
            raise HTTPException(503, 'Instagram session not configured')
        c.execute('UPDATE draft SET status=?, publish_at=? WHERE id=?', ('SCHEDULED', when, str(draft_id)))
        return response(row_for(c, draft_id))


@app.post('/stories/drafts/{draft_id}/cancel', dependencies=[Depends(auth)])
def cancel(draft_id: uuid.UUID, body: Confirmation):
    if body.confirmationText != f'ANNULLA STORY {draft_id}':
        raise HTTPException(403, 'Explicit cancellation required')
    with LOCK, db() as c:
        c.execute('BEGIN IMMEDIATE')
        r = row_for(c, draft_id)
        if r['status'] in ('DRAFT', 'SCHEDULED'):
            c.execute('UPDATE draft SET status=? WHERE id=?', ('CANCELLED', str(draft_id)))
        return response(row_for(c, draft_id))


def process_due(now=None):
    now = time.time() if now is None else now
    if not live_enabled():
        return
    with LOCK:
        with db() as c:
            c.execute('BEGIN IMMEDIATE')
            # Do not publish a stale story after a long outage.
            c.execute("UPDATE draft SET status='MISSED' WHERE status='SCHEDULED' AND publish_at < ?", (now - 900,))
            r = c.execute("SELECT id FROM draft WHERE status='SCHEDULED' AND publish_at <= ? ORDER BY publish_at,id LIMIT 1", (now,)).fetchone()
        if r:
            try:
                submit_locked(r['id'], ('SCHEDULED',))
            except HTTPException:
                with db() as c:
                    c.execute("UPDATE draft SET status='BLOCKED' WHERE id=? AND status='SCHEDULED'", (r['id'],))


def run_scheduler(stop):
    while not stop.wait(10):
        try:
            process_due()
        except Exception:
            # Never log credentials/SDK response; existing PUBLISHING remains for reconciliation.
            import logging
            logging.getLogger(__name__).warning('Instagram scheduler tick failed')
