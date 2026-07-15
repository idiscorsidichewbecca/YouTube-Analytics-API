# Chewbecca YouTube Analytics API

Applicazione Spring Boot che espone API private per analizzare il canale **I Discorsi di Chewbecca** e collegarle a un GPT tramite **Azioni**.

L'applicazione usa due livelli di autenticazione distinti:

1. **Google OAuth2**: il proprietario autorizza una sola volta l'accesso al proprio canale YouTube.
2. **Bearer API key**: il GPT usa una chiave privata per chiamare le API dell'applicazione, senza ricevere né conoscere i token Google.

## Funzionalità

- Riepilogo del canale:
  - iscritti attuali;
  - visualizzazioni lifetime;
  - numero di video pubblicati;
  - visualizzazioni, engaged views, like, commenti e condivisioni nel periodo;
  - tempo di visualizzazione;
  - durata media e percentuale media vista;
  - iscritti guadagnati, persi e netti.
- Elenco completo di video e Short con:
  - titolo, data, durata, URL e miniatura;
  - visualizzazioni, like e commenti lifetime;
  - metriche Analytics del periodo;
  - ricerca per titolo o video ID;
  - ordinamento e paginazione.
- Confronto aggregato tra video tradizionali e Short.
- Dettaglio di un singolo contenuto.
- Andamento giornaliero o mensile del canale e del singolo contenuto.
- Sorgenti di traffico e principali Paesi per contenuto.
- OpenAPI dedicato alle Azioni dei GPT.
- H2 locale e PostgreSQL per produzione.
- Migrazioni database con Flyway.
- Token OAuth cifrati con AES-256-GCM.

## Requisiti

- Java 21
- Maven 3.6.3 o successivo, oppure `./mvnw`
- Un progetto Google Cloud con:
  - **YouTube Data API v3** abilitata;
  - **YouTube Analytics API** abilitata;
  - OAuth consent screen configurata;
  - client OAuth2 di tipo **Web application**.

## 1. Configurazione Google Cloud

Nel progetto Google Cloud in cui hai creato il client OAuth2:

1. Abilita **YouTube Data API v3**.
2. Abilita **YouTube Analytics API**.
3. Nella schermata consenso OAuth, se l'app è in modalità `Testing`, aggiungi il tuo account Google tra i test user.
   In modalità `Testing`, per le app esterne il refresh token può scadere dopo 7 giorni: per un deploy stabile porta l'app in stato di produzione oppure prevedi di ripetere il collegamento.
4. Nel client OAuth2 aggiungi questo redirect URI locale:

```text
http://localhost:8080/oauth2/google/callback
```

Quando l'app sarà pubblicata, aggiungi anche il redirect URI HTTPS di produzione, ad esempio:

```text
https://youtube-api.tuodominio.it/oauth2/google/callback
```

Gli scope richiesti sono:

```text
https://www.googleapis.com/auth/youtube.readonly
https://www.googleapis.com/auth/yt-analytics.readonly
```

## 2. Configurazione locale

Copia il file di esempio:

```bash
cp .env.example .env
```

Genera le chiavi:

```bash
./scripts/generate-secrets.sh
```

Inserisci in `.env`:

```dotenv
GOOGLE_CLIENT_ID=...
GOOGLE_CLIENT_SECRET=...
GOOGLE_REDIRECT_URI=http://localhost:8080/oauth2/google/callback
ADMIN_USERNAME=admin
ADMIN_PASSWORD=...
GPT_ACTION_API_KEY=...
TOKEN_ENCRYPTION_KEY=...
```

Se il tuo account Google gestisce più canali o Brand Account, imposta anche:

```dotenv
YOUTUBE_EXPECTED_CHANNEL_ID=UCxxxxxxxxxxxxxxxxxxxxxx
```

Carica le variabili su macOS/Linux:

```bash
set -a
source .env
set +a
```

## 3. Avvio

```bash
./mvnw spring-boot:run
```

Oppure, con Maven già installato:

```bash
mvn spring-boot:run
```

Swagger UI:

```text
http://localhost:8080/swagger-ui.html
```

Health check:

```text
http://localhost:8080/actuator/health
```

## 4. Collegamento del tuo canale YouTube

Apri nel browser:

```text
http://localhost:8080/admin/youtube/connect
```

Il browser chiederà le credenziali Basic definite da `ADMIN_USERNAME` e `ADMIN_PASSWORD`, poi verrà aperta la schermata Google OAuth.

Autorizza l'account proprietario del canale **I Discorsi di Chewbecca**.

Verifica lo stato:

```bash
curl -u "$ADMIN_USERNAME:$ADMIN_PASSWORD" \
  http://localhost:8080/admin/youtube/status
```

Per scollegare il canale:

```bash
curl -X DELETE \
  -u "$ADMIN_USERNAME:$ADMIN_PASSWORD" \
  http://localhost:8080/admin/youtube/disconnect
```

## 5. Test delle API

Riepilogo ultimi 28 giorni completi:

```bash
curl -H "Authorization: Bearer $GPT_ACTION_API_KEY" \
  http://localhost:8080/api/v1/channel/summary
```

Riepilogo di un periodo preciso:

```bash
curl -G \
  -H "Authorization: Bearer $GPT_ACTION_API_KEY" \
  --data-urlencode "startDate=2026-06-01" \
  --data-urlencode "endDate=2026-06-30" \
  http://localhost:8080/api/v1/channel/summary
```

Short ordinati per visualizzazioni:

```bash
curl -G \
  -H "Authorization: Bearer $GPT_ACTION_API_KEY" \
  --data-urlencode "type=SHORT" \
  --data-urlencode "sortBy=VIEWS" \
  --data-urlencode "order=DESC" \
  --data-urlencode "size=50" \
  http://localhost:8080/api/v1/content
```

Ricerca di un contenuto per titolo:

```bash
curl -G \
  -H "Authorization: Bearer $GPT_ACTION_API_KEY" \
  --data-urlencode "query=Darth Vader" \
  http://localhost:8080/api/v1/content
```

Confronto aggregato video vs Short:

```bash
curl -H "Authorization: Bearer $GPT_ACTION_API_KEY" \
  http://localhost:8080/api/v1/content/summary
```

Dettaglio di un video:

```bash
curl -H "Authorization: Bearer $GPT_ACTION_API_KEY" \
  http://localhost:8080/api/v1/content/VIDEO_ID
```

## 6. Collegamento alle Azioni del GPT

Il file da importare è:

```text
openapi-gpt.yaml
```

Nel progetto trovi anche `gpt-action-instructions.txt`, da usare come base nelle istruzioni del GPT affinché scelga l'endpoint corretto e distingua sempre metriche lifetime e metriche del periodo.

Prima del deploy aggiorna entrambe le copie del contratto OpenAPI con lo script incluso:

```bash
./scripts/set-public-domain.sh https://youtube-api.tuodominio.it
```

Lo script sostituisce `https://YOUR_PUBLIC_DOMAIN` sia nel file da importare sia nella copia esposta dall'applicazione.

Nel builder del GPT:

1. Apri **Configura → Azioni → Crea nuova azione**.
2. Incolla o importa `openapi-gpt.yaml`.
3. In autenticazione scegli **API key**.
4. Seleziona **Bearer**.
5. Inserisci lo stesso valore configurato in `GPT_ACTION_API_KEY`.
6. Come URL della privacy policy usa `https://TUO_DOMINIO/privacy-policy.html` se il GPT viene condiviso pubblicamente.
7. Esegui i test delle azioni dal pannello Preview.

Il GPT non deve usare il client ID o il client secret Google. Questi restano soltanto nel backend Spring Boot.

## 7. Endpoint

| Metodo | Endpoint | Descrizione |
|---|---|---|
| GET | `/api/v1/channel/summary` | Riepilogo lifetime e periodo |
| GET | `/api/v1/channel/trend` | Trend giornaliero o mensile |
| GET | `/api/v1/content` | Video e Short paginati, filtrabili e ricercabili |
| GET | `/api/v1/content/summary` | Confronto aggregato video vs Short |
| GET | `/api/v1/content/{videoId}` | Dettaglio contenuto |
| GET | `/api/v1/content/{videoId}/trend` | Trend del contenuto |
| GET | `/api/v1/content/{videoId}/traffic-sources` | Sorgenti di traffico |
| GET | `/api/v1/content/{videoId}/countries` | Principali Paesi |
| GET | `/admin/youtube/connect` | Avvia Google OAuth2 |
| GET | `/admin/youtube/status` | Stato collegamento |
| DELETE | `/admin/youtube/disconnect` | Rimuove token salvati |

Date omesse: ultimi 28 giorni completi. Le date YouTube Analytics sono riferite al fuso `America/Los_Angeles`.

## 8. Riconoscimento degli Short

La YouTube Data API non espone un attributo diretto e affidabile `isShort`. L'app usa questa strategia:

1. override manuali;
2. contenuti pubblicati prima dell'introduzione di Shorts classificati come video;
3. hashtag `#short` o `#shorts`;
4. durata massima configurabile, con confidenza `MEDIUM`;
5. i contenuti oltre la durata massima sono classificati come video.

Puoi correggere casi particolari in `application.yml`:

```yaml
app:
  youtube:
    forced-short-ids:
      - VIDEO_ID_DA_FORZARE_COME_SHORT
    forced-video-ids:
      - VIDEO_ID_DA_FORZARE_COME_VIDEO
```

La risposta espone sempre:

- `contentType`;
- `contentTypeDetection`;
- `classificationConfidence`.

Nell'endpoint delle sorgenti di traffico, il valore `SHORTS` segnala visualizzazioni provenienti dal feed Shorts.

## 9. Database di produzione

Avvia PostgreSQL locale:

```bash
docker compose up -d postgres
```

Esegui con profilo produzione:

```bash
export SPRING_PROFILES_ACTIVE=prod
export DATABASE_URL=jdbc:postgresql://localhost:5432/chewbecca_youtube
export DATABASE_USERNAME=chewbecca
export DATABASE_PASSWORD=chewbecca
./mvnw spring-boot:run
```

In produzione il profilo `prod` blocca l'avvio se rileva le chiavi di sviluppo predefinite. Flyway crea e versiona automaticamente la tabella dei token OAuth; Hibernate è configurato in modalità `validate`.

## 10. Deploy

L'app deve essere esposta su HTTPS affinché il GPT possa chiamarla. Puoi pubblicarla su AWS Lightsail, ECS, App Runner, Render, Railway o un VPS Docker.

Build:

```bash
./mvnw clean package
```

Docker:

```bash
docker build -t chewbecca-youtube-analytics-api .
docker run --rm -p 8080:8080 --env-file .env chewbecca-youtube-analytics-api
```

Dopo il deploy:

1. aggiorna `GOOGLE_REDIRECT_URI` con il callback HTTPS;
2. aggiungi lo stesso URI nel client OAuth Google;
3. aggiorna il server in `openapi-gpt.yaml`;
4. visita `/admin/youtube/connect` sul dominio pubblico;
5. configura la API key nel GPT.

## Note sui dati

- Le statistiche `lifetime` arrivano dalla YouTube Data API.
- Le metriche del periodo arrivano dalla YouTube Analytics API.
- Il totale iscritti restituito dalla Data API può essere arrotondato anche per il proprietario del canale.
- Dal 30 aprile 2025, per gli Short `views` conta avvii e replay; `engagedViews` mantiene la precedente logica basata sulla visione oltre i primi secondi.
- Alcuni dati Analytics possono avere un ritardo di elaborazione; per questo il periodo predefinito termina ieri.

## Riferimenti ufficiali

- YouTube Analytics channel reports: https://developers.google.com/youtube/analytics/channel_reports
- YouTube Analytics metrics: https://developers.google.com/youtube/analytics/metrics
- YouTube Data API: https://developers.google.com/youtube/v3/docs
- Google OAuth web-server flow: https://developers.google.com/identity/protocols/oauth2/web-server
- Configurazione GPT Actions: https://help.openai.com/en/articles/9442513-configuring-actions-in-gpts
