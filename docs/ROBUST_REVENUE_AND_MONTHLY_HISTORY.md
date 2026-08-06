# Revenue robuste e storico mensile nativo

Questa release modifica l'integrazione YouTube Analytics in tre punti.

## Report economici separati

Le metriche economiche principali vengono richieste separatamente dalle metriche pubblicitarie opzionali.

Report indispensabile:

- views
- estimatedRevenue

Componenti economiche opzionali:

- estimatedAdRevenue
- estimatedRedPartnerRevenue

Metriche pubblicitarie opzionali:

- grossRevenue
- monetizedPlaybacks
- adImpressions
- playbackBasedCpm
- cpm

Se una componente secondaria non è supportata dalla combinazione di report, il totale `estimatedRevenue` resta comunque disponibile. La sincronizzazione non confonde più l'assenza di CPM o impression con l'assenza dei ricavi del video.

## Ricavi di tutti i contenuti

Il report con dimensione `video` usa filtri in blocchi fino a 500 ID e viene paginato tramite `maxResults` e `startIndex`, con ordinamento stabile per ID. Non viene eseguita una richiesta per ogni singolo video.

## Trend mensile

I trend mensili usano la dimensione nativa `month`; startDate ed endDate vengono allineate al primo giorno del mese. Il payload continua a riportare l'intervallo originariamente richiesto.

## Diagnostica integrazione

Nuovo endpoint protetto da API key:

`GET /api/v1/integration/youtube/status`

Espone anche `monetaryAnalyticsGranted`, usato dal Content Hub per evitare richieste economiche destinate a fallire.
