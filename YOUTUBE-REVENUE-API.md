# Modifica: ricavi YouTube

## Endpoint aggiunti

- `GET /api/v1/revenue/summary`
- `GET /api/v1/revenue/trend`
- `GET /api/v1/revenue/content`
- `GET /api/v1/revenue/content/{videoId}`
- `GET /api/v1/revenue/content/{videoId}/trend`

Parametri comuni: `startDate`, `endDate`, `currency` (default `EUR`). I trend accettano `granularity=DAY|MONTH`.

## Metriche restituite

- `estimatedRevenue`: ricavo netto stimato complessivo;
- `estimatedAdRevenue`: quota stimata da pubblicità;
- `estimatedPremiumRevenue`: quota stimata da YouTube Premium;
- `grossRevenue`;
- `monetizedPlaybacks`;
- `adImpressions`;
- `playbackBasedCpm`;
- `adImpressionCpm`;
- `estimatedRpm`, calcolato come ricavo stimato ogni 1.000 visualizzazioni;
- `monetizedPlaybackRatePercentage`.

## Operazione necessaria dopo il deploy

I token Google esistenti non possiedono automaticamente il nuovo scope monetario.

1. `DELETE /admin/youtube/disconnect`
2. aprire `GET /admin/youtube/connect`
3. completare nuovamente il consenso Google
4. controllare `GET /admin/youtube/status`: `monetaryAnalyticsGranted` deve essere `true`

## Verifiche effettuate

- parsing dei due file OpenAPI;
- compilazione sintattica delle classi modificate con Java 21 e stub delle dipendenze framework;
- smoke test dei calcoli RPM e percentuale di riproduzioni monetizzate.

La build Maven completa richiede accesso a Maven Central per scaricare Maven e le dipendenze.
