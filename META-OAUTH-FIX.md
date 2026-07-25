# Fix OAuth Meta - System User token

Correzioni applicate:

- supporto sia alla risposta token standard (`access_token` alla radice) sia alla risposta Facebook Login for Business con System User token (`data.access_token`);
- errore upstream più leggibile se Meta restituisce HTTP 200 senza un token riconoscibile;
- recupero delle Pagine autorizzate con fallback da `/me/accounts` a `/me?fields=accounts{...}` per i Business Integration System User token.

Dopo il deploy avviare un nuovo flusso da `/admin/meta/connect`: gli authorization code sono monouso.
