#!/usr/bin/env sh
set -eu

if [ "$#" -ne 1 ]; then
  echo "Uso: TELEGRAM_BOT_TOKEN=... TELEGRAM_WEBHOOK_SECRET=... $0 https://servizio.onrender.com" >&2
  exit 1
fi

: "${TELEGRAM_BOT_TOKEN:?TELEGRAM_BOT_TOKEN mancante}"
: "${TELEGRAM_WEBHOOK_SECRET:?TELEGRAM_WEBHOOK_SECRET mancante}"

DOMAIN=${1%/}
case "$DOMAIN" in
  https://*) ;;
  *) echo "Il dominio deve iniziare con https://" >&2; exit 1 ;;
esac

curl --fail-with-body --silent --show-error \
  --request POST \
  "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/setWebhook" \
  --data-urlencode "url=${DOMAIN}/api/integrations/telegram/webhook" \
  --data-urlencode "secret_token=${TELEGRAM_WEBHOOK_SECRET}" \
  --data-urlencode 'allowed_updates=["message"]' \
  --data-urlencode 'drop_pending_updates=true'

echo

curl --fail-with-body --silent --show-error \
  --request POST \
  "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/setMyCommands" \
  --data-urlencode 'commands=[
    {"command":"ping","description":"Verifica che il bot sia attivo"},
    {"command":"help","description":"Mostra tutti i comandi disponibili"},
    {"command":"topvideo","description":"Top 10 video per visualizzazioni"},
    {"command":"topshort","description":"Top 10 Short per visualizzazioni"},
    {"command":"adesso","description":"Ultimi 28 giorni contro i precedenti"},
    {"command":"trimestre","description":"Ultimi 3 mesi contro i precedenti"},
    {"command":"guadagni30","description":"Ricavi stimati degli ultimi 30 giorni"},
    {"command":"ripartenze","description":"Confronta le ripartenze configurate"},
    {"command":"confrontomese","description":"Confronta ultimo mese con YYYY-MM"},
    {"command":"confrontoanno","description":"Confronta anno corrente con YYYY"},
    {"command":"ultimovideo","description":"Confronta ultimo video con i precedenti"}
  ]'

echo
