# Chewbecca Social Analytics API

Applicazione Spring Boot privata per analizzare **I Discorsi di Chewbecca** su:

- YouTube e YouTube Analytics;
- Facebook Pages;
- Instagram Professional, inclusi post, caroselli, video, Reel e storie attive.

L'integrazione Meta è stata aggiunta senza sostituire o rinominare gli endpoint YouTube esistenti. Le API sono separate per prefisso:

```text
/api/v1/channel/**             YouTube: canale
/api/v1/content/**             YouTube: video e Short
/api/v1/revenue/**             YouTube: ricavi e monetizzazione
/api/v1/meta/facebook/**       Facebook
/api/v1/meta/instagram/**      Instagram
/api/v1/meta/overview          Facebook + Instagram
```

## Sicurezza

L'applicazione usa tre livelli distinti:

1. **Google OAuth 2.0**, per collegare il canale YouTube.
2. **Facebook Login for Business**, per collegare la Pagina Facebook e l'account Instagram professionale.
3. **Bearer API key**, usata dal GPT per chiamare `/api/v1/**` senza conoscere i token dei provider.

Gli endpoint amministrativi `/admin/**` richiedono HTTP Basic. I token Google e Meta sono persistiti nel database e cifrati con AES-256-GCM.

## Requisiti

- Java 21
- Maven 3.6.3+ oppure `./mvnw`
- Google Cloud con YouTube Data API v3 e YouTube Analytics API
- Meta App con Facebook Login for Business e account Instagram professionale collegato alla Pagina
- PostgreSQL in produzione; H2 è disponibile per lo sviluppo locale

## Configurazione locale

Crea il file delle variabili:

```bash
cp .env.example .env
./scripts/generate-secrets.sh
```

Riporta nel file `.env` i valori prodotti dallo script e configura Google e Meta.

Caricamento su macOS/Linux:

```bash
set -a
source .env
set +a
```

Avvio:

```bash
./mvnw spring-boot:run
```

Swagger UI:

```text
http://localhost:8080/swagger-ui.html
```

Health check:

```text
http://localhost:8080/actuator/health
```

## Configurazione Google / YouTube

Nel client OAuth Google registra:

```text
http://localhost:8080/oauth2/google/callback
```

In produzione aggiungi anche il callback HTTPS, per esempio:

```text
https://api.tuodominio.it/oauth2/google/callback
```

Variabili richieste:

```dotenv
GOOGLE_CLIENT_ID=...
GOOGLE_CLIENT_SECRET=...
GOOGLE_REDIRECT_URI=https://api.tuodominio.it/oauth2/google/callback
YOUTUBE_EXPECTED_CHANNEL_ID=UC...
```

Il collegamento Google richiede anche lo scope monetario:

```text
https://www.googleapis.com/auth/yt-analytics-monetary.readonly
```

Collegamento:

```text
GET /admin/youtube/connect
```

Dopo l'aggiornamento da una versione precedente, esegui una volta `DELETE /admin/youtube/disconnect` e poi riapri `GET /admin/youtube/connect`, altrimenti il token già salvato non potrà leggere i ricavi. `GET /admin/youtube/status` espone il campo `monetaryAnalyticsGranted` per verificarlo.

Stato e disconnessione:

```text
GET    /admin/youtube/status
DELETE /admin/youtube/disconnect
```

## Endpoint ricavi YouTube

Tutti gli endpoint richiedono la stessa Bearer API key delle altre API `/api/v1/**`.

| Metodo | Endpoint | Descrizione |
|---|---|---|
| GET | `/api/v1/revenue/summary` | Ricavi stimati complessivi del canale nel periodo |
| GET | `/api/v1/revenue/trend` | Trend giornaliero o mensile dei ricavi del canale |
| GET | `/api/v1/revenue/content` | Ricavi di ogni video e Short, filtrabili, ordinabili e paginati |
| GET | `/api/v1/revenue/content/{videoId}` | Ricavi di un singolo contenuto |
| GET | `/api/v1/revenue/content/{videoId}/trend` | Trend dei ricavi del singolo contenuto |

La valuta predefinita è `EUR`; è possibile passare un altro codice ISO 4217 con `currency`, ad esempio `USD`.

```bash
curl -G -H "Authorization: Bearer $GPT_ACTION_API_KEY" \
  --data-urlencode "startDate=2026-07-01" \
  --data-urlencode "endDate=2026-07-31" \
  --data-urlencode "currency=EUR" \
  https://api.tuodominio.it/api/v1/revenue/summary
```

```bash
curl -G -H "Authorization: Bearer $GPT_ACTION_API_KEY" \
  --data-urlencode "type=VIDEO" \
  --data-urlencode "sortBy=ESTIMATED_REVENUE" \
  --data-urlencode "order=DESC" \
  --data-urlencode "size=25" \
  https://api.tuodominio.it/api/v1/revenue/content
```

`estimatedRevenue` è il ricavo netto stimato più utile per capire quanto ha generato il canale o un video. I dati possono essere rettificati a fine mese e non rappresentano necessariamente il pagamento AdSense definitivo. `estimatedRpm` viene calcolato dall'applicazione come `estimatedRevenue / views * 1000`.

## Configurazione Meta / Facebook e Instagram

La Meta App deve usare la configurazione **Facebook Login for Business** con:

```text
Configuration ID: 1046575924525879
Token: System User Access Token, scadenza 60 giorni
Risorse richieste: Pagine, Account Instagram
```

Permessi configurati:

```text
business_management
instagram_basic
instagram_manage_insights
pages_read_engagement
pages_show_list
read_insights
```

Nel pannello Meta registra il callback esatto:

```text
http://localhost:8080/oauth2/meta/callback
```

In produzione:

```text
https://api.tuodominio.it/oauth2/meta/callback
```

Variabili richieste:

```dotenv
META_ENABLED=true
META_APP_ID=...
META_APP_SECRET=...
META_CONFIG_ID=1046575924525879
META_REDIRECT_URI=https://api.tuodominio.it/oauth2/meta/callback
META_GRAPH_API_VERSION=v25.0
```

Quando il portfolio contiene più coppie Pagina/Instagram, imposta almeno uno dei seguenti valori per evitare selezioni ambigue:

```dotenv
META_EXPECTED_PAGE_ID=...
META_EXPECTED_INSTAGRAM_ACCOUNT_ID=...
```

Collegamento:

```text
GET /admin/meta/connect
```

Il browser apre Facebook Login for Business; dopo l'autorizzazione il backend salva:

- System User Access Token cifrato;
- Page Access Token cifrato;
- ID e nome della Pagina;
- ID e username dell'account Instagram;
- autorizzazioni e scadenza del token.

Stato:

```bash
curl -u "$ADMIN_USERNAME:$ADMIN_PASSWORD" \
  https://api.tuodominio.it/admin/meta/status
```

Disconnessione:

```bash
curl -X DELETE \
  -u "$ADMIN_USERNAME:$ADMIN_PASSWORD" \
  https://api.tuodominio.it/admin/meta/disconnect
```

Il token Meta configurato scade dopo 60 giorni. Questa versione rileva la scadenza e restituisce un errore esplicito; prima della scadenza va ripetuto `/admin/meta/connect`. Non è implementato un rinnovo automatico non documentato.

`META_ENABLED=false` mantiene intatto il rilascio YouTube: l'app parte normalmente e gli endpoint Meta segnalano che l'integrazione è disabilitata.

## Endpoint Facebook

Tutti gli endpoint richiedono:

```http
Authorization: Bearer <GPT_ACTION_API_KEY>
```

| Metodo | Endpoint | Descrizione |
|---|---|---|
| GET | `/api/v1/meta/facebook/page` | Metadati della Pagina collegata |
| GET | `/api/v1/meta/facebook/summary` | Riepilogo del periodo |
| GET | `/api/v1/meta/facebook/trend` | Serie giornaliera delle metriche |
| GET | `/api/v1/meta/facebook/insights` | Insight generici con elenco metriche personalizzato |
| GET | `/api/v1/meta/facebook/posts` | Post filtrabili, ordinabili e paginati |
| GET | `/api/v1/meta/facebook/posts/{postId}` | Dettaglio post |
| GET | `/api/v1/meta/facebook/posts/{postId}/insights` | Insight lifetime del post |
| GET | `/api/v1/meta/facebook/videos` | Video Facebook |
| GET | `/api/v1/meta/facebook/reels` | Reel Facebook |
| GET | `/api/v1/meta/facebook/videos/{videoId}` | Dettaglio video |
| GET | `/api/v1/meta/facebook/videos/{videoId}/insights` | Insight lifetime del video |
| GET | `/api/v1/meta/facebook/top-content` | Classifica contenuti |

Esempi:

```bash
curl -G -H "Authorization: Bearer $GPT_ACTION_API_KEY" \
  --data-urlencode "startDate=2026-07-01" \
  --data-urlencode "endDate=2026-07-25" \
  https://api.tuodominio.it/api/v1/meta/facebook/summary
```

```bash
curl -G -H "Authorization: Bearer $GPT_ACTION_API_KEY" \
  --data-urlencode "type=REEL" \
  --data-urlencode "sortBy=REACH" \
  --data-urlencode "limit=10" \
  https://api.tuodominio.it/api/v1/meta/facebook/top-content
```

Con Graph API v25 la lettura diretta dell'edge Page Videos non è disponibile: gli endpoint video/Reel vengono quindi costruiti dai post pubblicati e dai relativi allegati. La classificazione dei Reel usa permalink e metadati disponibili e può essere euristica. Le risposte contengono `warnings` quando una metrica o una classificazione non è disponibile in modo univoco.

## Endpoint Instagram

| Metodo | Endpoint | Descrizione |
|---|---|---|
| GET | `/api/v1/meta/instagram/account` | Profilo e contatori dell'account |
| GET | `/api/v1/meta/instagram/summary` | Riepilogo del periodo |
| GET | `/api/v1/meta/instagram/trend` | Serie giornaliera delle metriche |
| GET | `/api/v1/meta/instagram/insights` | Insight generici, inclusi breakdown e metric type |
| GET | `/api/v1/meta/instagram/media` | Tutti i contenuti filtrabili e paginati |
| GET | `/api/v1/meta/instagram/reels` | Solo Reel |
| GET | `/api/v1/meta/instagram/stories` | Storie ancora attive |
| GET | `/api/v1/meta/instagram/media/{mediaId}` | Dettaglio contenuto |
| GET | `/api/v1/meta/instagram/media/{mediaId}/insights` | Insight lifetime del contenuto |
| GET | `/api/v1/meta/instagram/top-content` | Classifica contenuti |

Esempi:

```bash
curl -G -H "Authorization: Bearer $GPT_ACTION_API_KEY" \
  --data-urlencode "startDate=2026-07-01" \
  --data-urlencode "endDate=2026-07-25" \
  https://api.tuodominio.it/api/v1/meta/instagram/summary
```

```bash
curl -G -H "Authorization: Bearer $GPT_ACTION_API_KEY" \
  --data-urlencode "sortBy=VIEWS" \
  --data-urlencode "order=DESC" \
  --data-urlencode "includeInsights=true" \
  --data-urlencode "size=25" \
  https://api.tuodominio.it/api/v1/meta/instagram/reels
```

Per interrogare metriche non comprese nei riepiloghi predefiniti:

```bash
curl -G -H "Authorization: Bearer $GPT_ACTION_API_KEY" \
  --data-urlencode "metrics=views,reach,accounts_engaged,total_interactions" \
  --data-urlencode "metricType=total_value" \
  https://api.tuodominio.it/api/v1/meta/instagram/insights
```

## Endpoint combinato Meta

```text
GET /api/v1/meta/overview
```

Restituisce nello stesso payload il riepilogo Facebook e quello Instagram per il medesimo intervallo.

## Parametri comuni Meta

- `startDate`, `endDate`: formato ISO `YYYY-MM-DD`; default ultimi 28 giorni completi.
- `page`: indice da zero.
- `size`: da 1 a 100.
- `query`: ricerca testuale.
- `includeInsights`: carica anche gli insight per ogni contenuto; è più costoso in termini di chiamate API.
- `sortBy`: `PUBLISHED_AT`, `VIEWS`, `REACH`, `LIKES`, `COMMENTS`, `SHARES`, `SAVES`, `ENGAGEMENT`.
- `order`: `ASC` o `DESC`.
- `metrics`: lista separata da virgole, massimo 25 nomi validi.

Gli endpoint generici effettuano una chiamata separata per metrica. Se Meta ha rimosso o non supporta una metrica, l'intera risposta non fallisce: la metrica viene riportata in `warnings`.

## Endpoint YouTube esistenti

La struttura e i percorsi preesistenti sono invariati:

| Metodo | Endpoint | Descrizione |
|---|---|---|
| GET | `/api/v1/channel/summary` | Riepilogo lifetime e periodo |
| GET | `/api/v1/channel/trend` | Trend giornaliero o mensile |
| GET | `/api/v1/channel/subscription-status` | Iscritti/non iscritti |
| GET | `/api/v1/channel/devices` | Distribuzione per dispositivo |
| GET | `/api/v1/channel/operating-systems` | Sistemi operativi |
| GET | `/api/v1/channel/demographics` | Demografia |
| GET | `/api/v1/content` | Video e Short filtrabili e paginati |
| GET | `/api/v1/content/summary` | Confronto video/Short |
| GET | `/api/v1/content/{videoId}` | Dettaglio contenuto |
| GET | `/api/v1/content/{videoId}/trend` | Trend contenuto |
| GET | `/api/v1/content/{videoId}/traffic-sources` | Sorgenti di traffico |
| GET | `/api/v1/content/{videoId}/countries` | Paesi |
| GET | `/api/v1/content/{videoId}/retention` | Retention |

## Database

Flyway esegue:

```text
V1__create_youtube_credentials.sql
V2__create_meta_credentials.sql
```

La nuova tabella `meta_credentials` è indipendente da `youtube_credentials`, quindi i collegamenti e le scadenze dei due provider restano separati.

## GPT Actions

Il contratto da importare è:

```text
openapi-gpt.yaml
```

La stessa copia viene esposta da:

```text
/openapi-gpt.yaml
```

Imposta il dominio pubblico in entrambe le copie:

```bash
./scripts/set-public-domain.sh https://api.tuodominio.it
```

Nel GPT Builder scegli autenticazione **API key → Bearer** e usa `GPT_ACTION_API_KEY`.

Le istruzioni operative aggiornate sono in `gpt-action-instructions.txt` e obbligano il GPT a distinguere sempre la piattaforma e il tipo di metrica.

## Rilascio su Render

### Chewbecca-Strategist nel gruppo Telegram

Il progetto include un bot topic-aware senza AI e senza costi OpenAI. Riconosce
comandi e alcune frasi predefinite, interroga direttamente i servizi YouTube e
calcola in Java classifiche, variazioni percentuali e confronti temporali. Il
GPT configurato in ChatGPT e le 44 Actions esistenti rimangono invariati.

Configurazione Render, variabili, webhook e comandi sono descritti in
[`docs/TELEGRAM-STRATEGIST.md`](docs/TELEGRAM-STRATEGIST.md).

Mantieni tutte le variabili YouTube esistenti e aggiungi:

```dotenv
META_ENABLED=true
META_APP_ID=...
META_APP_SECRET=...
META_CONFIG_ID=1046575924525879
META_REDIRECT_URI=https://<servizio>.onrender.com/oauth2/meta/callback
META_GRAPH_API_VERSION=v25.0
META_EXPECTED_PAGE_ID=...
META_EXPECTED_INSTAGRAM_ACCOUNT_ID=...
```

Nel pannello Meta, `META_REDIRECT_URI` deve coincidere carattere per carattere con l'URI OAuth valido registrato.

Non inserire `META_APP_SECRET`, token Meta o chiavi di cifratura nel repository.

## Ottimizzazione ricavi per contenuto

`GET /api/v1/revenue/content` ora recupera il report monetario raggruppato per video usando `startIndex` e pagine YouTube da 200 righe. Non esegue più una richiesta separata per ciascun video oltre i primi 200.

La dimensione massima della risposta applicativa è stata portata a 500, così il Content Hub può importare tutti i contenuti del canale con una sola chiamata paginata lato applicazione.

## Revenue robuste e storico mensile

La release corrente separa i ricavi stimati principali dalle metriche pubblicitarie opzionali, pagina il report per video e usa la dimensione mensile nativa. È inoltre disponibile `GET /api/v1/integration/youtube/status` per verificare il permesso monetario. Vedi `docs/ROBUST_REVENUE_AND_MONTHLY_HISTORY.md`.


## Plugin privato ChatGPT Sites (nuovo)

Per controllare la pubblicazione degli YouTube Shorts interamente da una chat di ChatGPT Plus, usa il bridge REST isolato `/internal/chatgpt/shorts/**` e crea un plugin personale ospitato in ChatGPT Sites. I file di riferimento e i passaggi sono in [`docs/CHATGPT-SITES-CHEWBECCA.md`](docs/CHATGPT-SITES-CHEWBECCA.md). Le richieste di pubblicazione necessitano della chiave separata `CHATGPT_BRIDGE_API_KEY` e della conferma esplicita `CONFERMO <id-bozza>`. **Le Actions del GPT storico e il relativo token non possono pubblicare.**
