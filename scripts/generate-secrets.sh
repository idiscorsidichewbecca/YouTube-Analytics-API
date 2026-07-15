#!/usr/bin/env sh
set -eu
printf 'GPT_ACTION_API_KEY=%s\n' "$(openssl rand -hex 32)"
printf 'ADMIN_PASSWORD=%s\n' "$(openssl rand -hex 24)"
printf 'TOKEN_ENCRYPTION_KEY=%s\n' "$(openssl rand -base64 32 | tr -d '\n')"
