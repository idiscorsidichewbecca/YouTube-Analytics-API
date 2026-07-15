# Architettura

```text
GPT Action
   |  Authorization: Bearer <GPT_ACTION_API_KEY>
   v
Spring Boot API (/api/v1/**)
   |
   +--> YouTubeReportService
           |
           +--> YouTube Data API v3
           |      metadata + statistiche lifetime
           |
           +--> YouTube Analytics API v2
                  metriche nel periodo + trend + breakdown

Amministratore
   |  HTTP Basic
   v
/admin/youtube/connect
   |
   v
Google OAuth 2.0 Authorization Code
   |
   v
Token cifrati AES-256-GCM nel database
```

## Confini di sicurezza

- Il GPT conosce soltanto la API key del backend.
- Client secret, access token e refresh token Google restano nel backend.
- Gli endpoint amministrativi non sono inclusi nel contratto OpenAPI delle Azioni.
- Il refresh token è persistito cifrato; la chiave di cifratura arriva da variabile d'ambiente.
- Il callback OAuth verifica un parametro `state` monouso con scadenza.

## Dati

- YouTube Data API: canale, upload playlist, metadata, durata, privacy, visualizzazioni/like/commenti lifetime.
- YouTube Analytics API: views, engaged views, like, commenti, condivisioni, watch time, durata media, percentuale media vista e iscritti guadagnati/persi nel periodo.
- Flyway gestisce lo schema; H2 è previsto per sviluppo, PostgreSQL per produzione.
