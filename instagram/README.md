# Microservizio Chewbecca Instagram

Vedi `../README-INSTAGRAM.md` per integrazione Spring AI/MCP, deploy e limiti. Per il test locale:

```bash
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
export SERVICE_TOKEN=$(python -c 'import secrets; print(secrets.token_urlsafe(48))')
export ENABLE_LIVE=false
export DATA_DIR=./data
uvicorn app:app --host 127.0.0.1 --port 10000 --workers 1
```

API interne autenticata con `Authorization: Bearer $SERVICE_TOKEN`:
- `POST /stories/drafts` multipart `image`, `kind`, `link`, `question`, `options_json`.
- `GET /stories/drafts/{id}` stato.
- `POST /stories/drafts/{id}/publish` JSON `confirmationText` = `CONFERMO STORY <id>`.
- `POST /stories/drafts/{id}/schedule` JSON `publishAt` e `confirmationText` = `CONFERMO STORY <id> <publishAt>` (ISO con secondi e offset, no frazioni).
- `POST /stories/drafts/{id}/cancel` JSON `confirmationText` = `ANNULLA STORY <id>`.
- `GET /stories/quiz-history` storico.
- `GET /health` liveness (non prova autenticazione Instagram).

Test locali: `pip install pytest httpx` e `python -m pytest -q`. Non pubblicano su Instagram. `init_session.py` è un comando interattivo per account secondario; non esporlo in rete. API privata: non garantita; lasciare live disabilitato finché non sono completate le verifiche.
