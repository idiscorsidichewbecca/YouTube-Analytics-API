# Architettura

```text
GPT Action
   |  Authorization: Bearer <GPT_ACTION_API_KEY>
   v
Spring Boot API (/api/v1/**)
   |
   +--> YouTube (/api/v1/channel, /api/v1/content)
   |      +--> YouTube Data API v3
   |      +--> YouTube Analytics API v2
   |
   +--> Meta overview (/api/v1/meta/overview)
   |
   +--> Facebook (/api/v1/meta/facebook/**)
   |      +--> Meta Graph API /{page-id}
   |      +--> Page/post/video insights
   |
   +--> Instagram (/api/v1/meta/instagram/**)
          +--> Meta Graph API /{ig-user-id}
          +--> Account/media/Reel/story insights

Amministratore
   |  HTTP Basic
   +--> /admin/youtube/connect --> Google OAuth 2.0
   +--> /admin/meta/connect    --> Facebook Login for Business

OAuth callbacks pubblici
   +--> /oauth2/google/callback
   +--> /oauth2/meta/callback

Database
   +--> youtube_credentials: token Google cifrati
   +--> meta_credentials: token Meta cifrati + asset selezionati
```

## Separazione dei provider

L'integrazione Meta è additiva e non modifica i package o i mapping YouTube esistenti. I provider hanno:

- proprietà separate (`app.google`, `app.meta`);
- entità e migrazioni separate;
- servizi OAuth separati;
- cache separate;
- controller e prefissi API distinti;
- gestione esplicita delle rispettive scadenze.

## Flusso Meta

```text
/admin/meta/connect
   -> authorization URL con config_id
   -> Facebook Login for Business
   -> /oauth2/meta/callback?code=...&state=...
   -> scambio authorization code
   -> debug del token e lettura scadenza/scopes
   -> /me/accounts
   -> selezione Pagina + Instagram Business Account
   -> cifratura e persistenza System User/Page token
```

Quando vengono autorizzate più coppie Pagina/Instagram, la selezione è resa deterministica con `META_EXPECTED_PAGE_ID` o `META_EXPECTED_INSTAGRAM_ACCOUNT_ID`.

## Client Graph API

`MetaGraphClient` centralizza:

- versione Graph configurabile;
- Bearer token;
- `appsecret_proof` HMAC-SHA256;
- paginazione cursor-based;
- parsing degli errori Meta;
- conversione del code `190` in richiesta di riconnessione.

Gli insight generici vengono caricati una metrica alla volta. In questo modo una metrica deprecata o non applicabile produce un `warning` senza annullare le altre metriche valide.

## Confini di sicurezza

- Il GPT conosce soltanto `GPT_ACTION_API_KEY`.
- App secret e token Google/Meta restano nel backend.
- Gli endpoint amministrativi non sono inclusi nelle Actions.
- I callback OAuth verificano uno `state` monouso con scadenza.
- I token sono cifrati con la stessa infrastruttura AES-256-GCM già presente.
- `META_ENABLED=false` consente di rilasciare la versione senza configurare Meta.

## Dati e limiti

- I riepiloghi di account/Pagina usano un periodo; i dettagli dei singoli contenuti espongono insight lifetime disponibili.
- Le storie Instagram elencabili sono quelle ancora attive.
- `includeInsights=true` moltiplica le chiamate Graph API perché ogni contenuto richiede insight dedicati.
- Meta può rendere alcune metriche non disponibili in base a versione, tipo di contenuto, età del contenuto o autorizzazioni. Le risposte includono `warnings`.
- Con Graph API v25 video e Reel Facebook vengono estratti dai post pubblicati e dagli allegati, perché l'edge Page Videos non è leggibile. La classificazione Reel può quindi richiedere un'euristica.

## Persistenza

- Flyway gestisce H2 e PostgreSQL.
- `V1` crea le credenziali YouTube.
- `V2` crea le credenziali Meta.
- Hibernate usa `ddl-auto=validate`, quindi il deploy fallisce in modo esplicito se lo schema non è coerente.
