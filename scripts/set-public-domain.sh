#!/usr/bin/env sh
set -eu

if [ "$#" -ne 1 ]; then
  echo "Uso: $0 https://youtube-api.tuodominio.it" >&2
  exit 1
fi

DOMAIN=${1%/}
case "$DOMAIN" in
  https://*) ;;
  *) echo "Il dominio deve iniziare con https://" >&2; exit 1 ;;
esac

BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ROOT_SCHEMA="$BASE_DIR/openapi-gpt.yaml"
STATIC_SCHEMA="$BASE_DIR/src/main/resources/static/openapi-gpt.yaml"

python3 - "$ROOT_SCHEMA" "$STATIC_SCHEMA" "$DOMAIN" <<'PY'
from pathlib import Path
import sys
root = Path(sys.argv[1])
static = Path(sys.argv[2])
domain = sys.argv[3]
text = root.read_text()
old_lines = [line for line in text.splitlines() if line.strip().startswith('- url:')]
if not old_lines:
    raise SystemExit('Nessun server URL trovato nel contratto OpenAPI')
lines = text.splitlines()
for index, line in enumerate(lines):
    if line.strip().startswith('- url:'):
        indent = line[:len(line) - len(line.lstrip())]
        lines[index] = f'{indent}- url: {domain}'
        break
updated = '\n'.join(lines) + '\n'
root.write_text(updated)
static.write_text(updated)
PY

echo "OpenAPI aggiornato con: $DOMAIN"
