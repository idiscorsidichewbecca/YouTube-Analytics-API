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
