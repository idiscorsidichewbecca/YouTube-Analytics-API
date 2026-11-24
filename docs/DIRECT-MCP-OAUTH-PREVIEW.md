# MCP diretto su Render — implementazione iniziale (read-only)

> **Non distribuire senza compilazione, test di sicurezza OAuth e verifica reale ChatGPT.** Questo codice è una proposta di integrazione, **non** un server certificato o un'autenticazione end-to-end già verificata.

## Perché

Il vecchio OAuth Google è un client per YouTube. Per collegare **ChatGPT come client** serve un Authorization Server che emetta token per la risorsa MCP. Questo ramo aggiunge Spring Authorization Server con **client predefinito pubblico**, senza DCR/CIMD (non dichiararli nei metadati), `authorization_code` + PKCE S256, login dell'admin esistente. L'endpoint MCP e un solo tool read-only sono forniti tramite Spring AI 1.1.x per Boot 3.5.x.

## Configurazione Render (NON ancora in produzione)

- `MCP_ENABLED=false` finché non hai compilato e verificato le catene Spring Security
- `MCP_PUBLIC_URL=https://<tuo-backend>.onrender.com` (senza slash finale)
- `MCP_REDIRECT_URI`: usare l'URI **esatto mostrato da ChatGPT**. La default è l'URI stabile `https://chatgpt.com/connector_platform_oauth_redirect`, che ChatGPT usa quando la metadata dell'AS e l'identificazione dell'issuer sono compatibili. Se ChatGPT mostra un callback specifico, configurare quello esatto.

In ChatGPT, quando si collega **il backend Render** con `https://<tuo-backend>.onrender.com/mcp`, scegliere OAuth e inserire nelle impostazioni avanzate:

- Client ID: `chewbecca-chatgpt`
- Client secret: **vuoto** (client pubblico, PKCE)

**Non** usare l'URL `/mcp` del Site e **non** usare Google client ID. La password admin deve essere robusta e non comunicata nella chat.

## Verifiche richieste (pre-produzione)

1. App avvia senza modifiche su `MCP_ENABLED=false`.
2. Con MCP abilitato, `GET /.well-known/oauth-protected-resource` restituisce la metadata corretta.
3. `GET /.well-known/oauth-authorization-server` espone authorize/token/scopes e PKCE S256.
4. `POST /mcp` senza token restituisce 401 e header `WWW-Authenticate` con resource metadata.
5. Login owner/admin OAuth, test authorization code + PKCE con callback esatto ChatGPT.
6. Controllo rigoroso `iss`, `aud`, `exp`, `shorts:read`; un JWT Google NON è accettabile.
7. `tools/list` presenta unicamente `chewbecca_recommend_slot`; `tools/call` legge Analytics effettivi.
8. Rieseguire regressione sugli endpoint `/api/v1`, `/admin`, webhook Telegram e callback OAuth Google/Meta.

## Limiti prima di produzione

- Il grant e le chiavi RSA sono **in memoria**: un riavvio Render invalida i token. Per stabilità produttiva passare a `JdbcOAuth2AuthorizationService` e chiavi persistenti e protette.
- La metadata OAuth deve essere verificata con il plugin editor reale. Non dichiarare CIMD o DCR finché non implementati e testati.
- La richiesta `resource` di OAuth (RFC 8707) va validata e propagata nell'autorizzazione; la prima versione forza un `aud` singolo ma non implementa il pieno scambio `resource` richiesto dalle specifiche MCP: quindi **non è ancora pronta per un deploy pubblico**, ed è possibile che ChatGPT rifiuti il collegamento.
- L'attuale login del progetto usa HTTP Basic e, in modalità MCP, formLogin: rivalutare CSRF del login prima di aprire la porta ad utenti esterni.
- Nessun tool di pubblicazione, creazione bozza o invio MP4 è stato aggiunto al server MCP diretto. In questa versione è disponibile solo la lettura.
