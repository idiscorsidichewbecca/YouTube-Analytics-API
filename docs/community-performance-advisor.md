# Chewbecca Studio — Community Manager e Performance Advisor

L'aggiornamento aggiunge i due moduli a Java 21, Spring Boot 3.5.16 e Spring AI 1.1.8. Usa la connessione Google già configurata e lo stesso database. Flyway applica V9, V10 e V11. Lascia abilitati i flag già presenti sul servizio. Tutte le nuove integrazioni sono opt-in.

## Render

| Variabile | Valore predefinito | Effetto |
| --- | --- | --- |
| `APP_COMMUNITY_ENABLED` | `false` | Espone gli strumenti per i commenti se MCP è abilitato |
| `APP_COMMUNITY_DRY_RUN` | `true` | Simula le pubblicazioni di risposte. Imposta `false` per abilitarle dopo le conferme |
| `APP_PERFORMANCE_ENABLED` | `false` | Espone analisi, benchmark, proposte e storico se MCP è abilitato |
| `APP_PERFORMANCE_DRY_RUN` | `true` | Simula gli aggiornamenti di titoli, descrizioni e miniature |
| `APP_PERFORMANCE_REPORTING_ENABLED` | `false` | Importa i CSV delle impressioni e del CTR delle miniature |
| `APP_PERFORMANCE_REPORTING_CREATE_JOB` | `false` | Crea i reporting job mancanti per i tipi abilitati, se Google li offre al canale |
| `APP_PERFORMANCE_REPORTING_CRON` | `0 20 */6 * * *` | Frequenza dell'importazione reach, in ora italiana |
| `APP_PERFORMANCE_END_SCREEN_REPORTING_ENABLED` | `false` | Importa i CSV delle schermate finali |
| `APP_PERFORMANCE_END_SCREEN_REPORTING_CRON` | `0 45 */6 * * *` | Frequenza dell'importazione schermate finali, in ora italiana |
| `APP_PERFORMANCE_EXTENDED_REPORTING_ENABLED` | `false` | Importa i CSV di reach per sorgente e dispositivo e di utilizzo dei sottotitoli |
| `APP_PERFORMANCE_EXTENDED_REPORTING_CRON` | `0 35 */6 * * *` | Frequenza di importazione dei due report estesi, in ora italiana |
| `APP_PERFORMANCE_EVALUATIONS_ENABLED` | `false` | Raccoglie misure successive agli interventi già applicati |
| `APP_PERFORMANCE_EVALUATIONS_CRON` | `0 10 6 * * *` | Rivalutazioni automatiche, in ora italiana |
| `APP_PERFORMANCE_EMAIL_ENABLED` | `false` | Invia il riepilogo deterministico al destinatario configurato |
| `APP_PERFORMANCE_EMAIL_CRON` | `0 30 8 * * MON` | Lunedì alle 08:30, ora italiana |
| `APP_PERFORMANCE_EMAIL_FROM` / `APP_PERFORMANCE_EMAIL_TO` | vuoto | Mittente e destinatario SMTP |
| `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD` | nessuna configurazione SMTP | Server, porta e credenziali del provider |
| `SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH`, `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE` | dipende dal provider | Autenticazione e STARTTLS del provider SMTP |

Per la prima verifica, abilita i due moduli e lascia i due dry-run a `true`. Il dry-run blocca le scritture editoriali su YouTube e permette bozza, anteprima e controlli. Reporting ed email hanno flag propri e restano spenti finché non sono abilitati. Il provisioning dei job richiede separatamente `APP_PERFORMANCE_REPORTING_CREATE_JOB=true`. I job trovati vengono riutilizzati. Una rilettura di reporting può richiedere più di un ciclo dopo la creazione.

Mantieni le variabili MCP, OAuth e il disco H2 già utilizzati. L'esposizione dei nuovi strumenti dipende anche da `MCP_ENABLED=true` e l'autorizzazione a pubblicare usa lo scope MCP esistente `shorts:write`. Dopo il rilascio aggiorna o riconnetti il catalogo del plugin e controlla `tools/list`. Il piano gratuito di Render blocca l'uscita SMTP sulle porte 25, 465 e 587. L'email richiede una combinazione di istanza e provider con connettività SMTP. [Render – Free instances](https://render.com/docs/free#outbound-smtp-traffic)

## Google Cloud e OAuth

Abilita sul progetto OAuth già in uso **YouTube Data API v3**, **YouTube Analytics API** e **YouTube Reporting API**. Il codice riusa gli scope già richiesti:

| Scope | Operazioni |
| --- | --- |
| `https://www.googleapis.com/auth/youtube.readonly` | Catalogo, letture Data API e requisito aggiuntivo di `reports.query` |
| `https://www.googleapis.com/auth/yt-analytics.readonly` | Metriche Analytics e reporting job non monetari |
| `https://www.googleapis.com/auth/youtube.force-ssl` | Pubblicazione risposte e aggiornamento snippet |
| `https://www.googleapis.com/auth/youtube.upload` | Caricamento della miniatura tramite il client esistente |
| `https://www.googleapis.com/auth/yt-analytics-monetary.readonly` | Ricavi e performance pubblicitarie, secondo le autorizzazioni del canale |

Ricollega Google da `/admin/youtube/connect` solo se la credenziale esistente non ha lo scope necessario per l'azione. L'abilitazione della Reporting API sul progetto Cloud è indipendente dal consenso OAuth. [YouTube Reporting REST](https://developers.google.com/youtube/reporting/v1/reference/rest/) · [YouTube Analytics reports.query](https://developers.google.com/youtube/analytics/reference/reports/query)

## Commenti e approvazioni

`chewbecca_list_comments` usa il canale proprietario e restituisce un token per la pagina seguente. Continua anche se una pagina filtrata è vuota. Le API non forniscono un filtro per la data dell'ultima risposta a un thread, perciò la scansione completa può raggiungere thread storici. Una scansione interrotta da quota rimane parziale. Sono esclusi i post Community e le chat live. Le date dei commenti usano Europe/Rome. Per i thread in revisione e il probabile spam si possono scorrere separatamente i filtri `heldForReview` e `likelySpam`, in base alle righe visibili alla credenziale. Il riepilogo settimanale automatico conta i commenti pubblicati. Le risposte in un thread sono indirizzate al commento principale, secondo il modello supportato da YouTube. Per prevenire doppioni il backend tratta una replica esistente del canale come risposta al thread. [commentThreads.list](https://developers.google.com/youtube/v3/docs/commentThreads/list) · [comments.list](https://developers.google.com/youtube/v3/docs/comments/list) · [comments.insert](https://developers.google.com/youtube/v3/docs/comments/insert)

Ogni batch ha fino a 25 righe, numeri stabili, revisione e stati per riga. Il prompt va a ChatGPT. Il servizio conserva la bozza e pubblica soltanto le righe approvate della revisione confermata. Correggere una riga la riporta in bozza. In caso di quota, le righe ancora da elaborare restano approvate. Con un esito incerto, la chiamata successiva cerca una risposta corrispondente pubblicata dal canale. Se non può accertare l'esito, segnala la verifica manuale. Le istruzioni eventualmente presenti in testo, descrizione e sottotitoli non sono autorizzazioni. Gli strumenti di pubblicazione sono marcati come operazioni esterne di scrittura e il backend verifica scope, revisione e conferma.

## Metriche e disponibilità

| Famiglia | Fonte e dati effettivi | Limiti |
| --- | --- | --- |
| Catalogo | Data API, titolo, descrizione, durata, formato classificato, URL miniatura | Il formato dei video brevi può dipendere dal classificatore e dalle eccezioni già configurate |
| Reach | Reporting `channel_reach_basic_a1`, impressioni e CTR per video, canale e data | Il job deve essere offerto e produrre CSV. Il CTR di periodo è ponderato sulle impressioni con CTR disponibile. Una riga mancante rimane assente |
| Visualizzazioni e watch time | Analytics `views`, `estimatedMinutesWatched`, durata e percentuale media, trend giornaliero | Le query aggregate non espongono sempre l'ultimo giorno effettivo. Quello osservabile è indicato per i dati giornalieri |
| Traffico | Analytics, incluse ricerca, suggeriti, navigazione, esterno e feed Shorts quando esposti | Termini e dettagli sono soggetti a soglie privacy. Il reach di base non ha segmentazione per sorgente |
| Retention | Analytics, `audienceWatchRatio`, `relativeRetentionPerformance` e curva per avanzamento del video | I punti a 3, 10, 30 e 60 secondi sono approssimazioni sulla risoluzione della curva. I cali sono candidati da rivedere. Un rapporto sopra 1 identifica un segmento compatibile con revisioni/ripetizioni |
| Engagement | Analytics, like, commenti, condivisioni, iscritti acquisiti e persi | `RATIOS` espone engagement e conversione iscritti sulla stessa finestra e indica gli iscritti netti: le formule sono riportate sotto |
| Schermate finali | Reporting `channel_end_screens_a2`, impressioni, clic e CTR complessivo e per tipo di elemento | La suddivisione storica è ricostruibile per i CSV ancora scaricabili. Il tasso aggregato usa clic / impressioni |
| Shorts | Analytics, `engagedViews`, retention, durata e percentuale media, visualizzazioni da sorgente SHORTS e riproduzioni dalla posizione SHORTS_FEED | La fonte di traffico e la posizione di riproduzione misurano fenomeni diversi. Questo flusso non dispone di Shown in feed o Viewed vs swiped away |
| Ricavi | Analytics, `estimatedRevenue`, CPM e riproduzioni monetizzate; RPM stimato come ricavo stimato × 1.000 / visualizzazioni per i video lunghi oppure / engaged views per gli Shorts | Permessi monetari e report compatibile. Le impressioni pubblicitarie sono una metrica distinta dalla reach delle miniature |

Ogni sezione indica fonte, periodo richiesto, timestamp di raccolta e disponibilità. I CSV Reporting usano giornate PST fisse (UTC−8) e indicano data del report e tempi di generazione e importazione. Analytics normalmente elabora i dati con ritardo di 48–72 ore. Le finestre di uno e due giorni sono giorni di calendario Pacific dalla data di pubblicazione, anche se il video è uscito a metà giornata. Per 7 e 28 giorni vale lo stesso criterio. Nessuna finestra rappresenta le prime 24, 48, 168 o 672 ore esatte. [Dati Analytics](https://developers.google.com/youtube/analytics/data_model) · [Report canale](https://developers.google.com/youtube/reporting/v1/reports/channel_reports) · [Definizioni Reporting](https://developers.google.com/youtube/reporting/v1/reports/metrics)

L'importazione considera tutte le pagine dei job disponibili e sostituisce atomicamente una giornata quando Google pubblica una versione più recente. Recupera lo storico ancora scaricabile. Per un nuovo job Google documenta il backfill dei 30 giorni precedenti. I CSV storici scadono dopo 30 giorni dalla generazione, quelli ordinari dopo 60. La copertura effettiva resta legata ai report disponibili. La prima settimana del video può quindi restare senza impressioni storiche. I dati vengono rinnovati dopo verifica del canale e dei video. Le bozze dei commenti e i dati operativi non rinnovati vengono rimossi entro la finestra di 28 giorni. La disconnessione locale cancella sincronicamente i dati dei due nuovi moduli per il canale. [Panoramica Reporting](https://developers.google.com/youtube/reporting/v1/reports) · [Norme per sviluppatori YouTube API](https://developers.google.com/youtube/terms/developer-policies)

## Sezioni metriche avanzate

Usa `chewbecca_video_metrics` per un video e `chewbecca_channel_metrics` per il canale. Raggruppa le sezioni per argomento durante gli approfondimenti. Il tool riporta il periodo richiesto, l'ultima data osservata quando presente e lo stato di disponibilità. I dati Reporting a livello canale riguardano gli upload attribuibili al canale.

| Area | Sezioni aggiuntive | Dati |
| --- | --- | --- |
| Reach | `REACH_BY_SOURCE`, `REACH_BY_SOURCE_DETAIL`, `REACH_BY_DEVICE`, `REACH_BY_OS` | Impressioni e CTR delle miniature, segmentati dal report `channel_reach_combined_a1`. Il CTR pondera soltanto le impressioni con CTR misurato. Le etichette sono i codici originali di Google. Le liste mostrano fino a 25 segmenti per sezione e indicano l'eventuale troncamento |
| Performance Shorts | `SHORTS_FEED`, `ENGAGED_VIEWS_DAILY` | Views, engaged views e watch time sul player Shorts, più trend complessivo delle engaged views. Il benchmark Shorts aggiunge retention a 3 e 10 secondi e durata media |
| Retention | `RETENTION_SEGMENTS` | Conteggi ufficiali di avvii, abbandoni e riproduzioni per segmento, con posizione in secondi stimata dalla durata |
| Audience | `COUNTRY`, `DEMOGRAPHICS`, `SUBSCRIPTION_STATUS`, `UNIQUE_VIEWERS_DAILY` | Paesi, età e genere, iscritti e non iscritti. Il report giornaliero `uniques` viene richiesto separatamente e può non essere restituito da Google. Le righe degli spettatori unici sono giornaliere e non vanno sommate |
| Distribuzione | `DEVICE`, `OPERATING_SYSTEM`, `DEVICE_AND_OS`, `YOUTUBE_PRODUCT`, `LIVE_OR_ON_DEMAND`, `PLAYBACK_LOCATION`, `EMBEDDED_PLAYERS`, `PLAYLIST_REFERRERS`, `CHANNEL_REFERRERS` | Ripartizioni per dispositivo, sistema, prodotto, dirette e on demand, player e referrer |
| Interazioni | `ENGAGEMENT_DETAIL`, `ENGAGEMENT_DAILY`, `SHARES_BY_SERVICE`, `RATIOS`, `CARDS`, `END_SCREENS_BY_TYPE`, `ANNOTATIONS_HISTORICAL` | Dislike, aggiunte e rimozioni da playlist, trend, servizi di condivisione, percentuali editoriali, schede, tipi di schermata finale e dati storici delle annotazioni quando presenti |
| Contatori correnti | `LIFETIME_COUNTERS` | Snapshot cumulativo Data API di visualizzazioni, like, commenti e dislike quando autorizzati e restituiti. Si applica solo a un video e non viene usato come dato di periodo |
| Sottotitoli | `SUBTITLES` | Views, engaged views e minuti visti per lingua dei sottotitoli e lingua della traduzione, con le componenti Premium quando fornite |
| Live | `LIVE_CONCURRENT`, `LIVE_CONCURRENT_TIMELINE` | Media e picco degli spettatori contemporanei di una diretta, anche nel tempo quando disponibili |
| Entrate | `PREMIUM`, `MONETARY_COMPONENTS`, `MONETARY_DAILY`, `ADS_BY_TYPE` | Views e watch time Premium, ricavi stimati da annunci e Premium, andamento giornaliero, impressioni pubblicitarie e CPM per tipo di annuncio. Le metriche economiche usano USD e richiedono il Programma partner e lo scope monetario |

`RATIOS` calcola engagement come (like + commenti + condivisioni) × 100 / views, conversione iscritti come iscritti acquisiti × 100 / views e saldo come acquisiti meno persi. I valori restano assenti quando mancano gli input necessari. L'RPM è una stima sui ricavi effettivamente restituiti dall'API. [Report Analytics di canale](https://developers.google.com/youtube/analytics/channel_reports) · [RPM di YouTube](https://support.google.com/youtube/answer/9314357)

I report estesi riusano OAuth, Reporting API e `APP_PERFORMANCE_REPORTING_CREATE_JOB`. `chewbecca_reporting_status` restituisce lo stato di ogni nuovo job in `extendedReports`. La raccolta aggiorna il giorno quando YouTube rilascia un CSV più recente e applica le regole di verifica e cancellazione dei report esistenti. [Report Reporting di canale](https://developers.google.com/youtube/reporting/v1/reports/channel_reports) · [Codici delle dimensioni Reporting](https://developers.google.com/youtube/reporting/v1/reports/dimensions)

Dati come Shown in feed, Viewed vs swiped away e spettatori di ritorno restano visibili in Studio attraverso i relativi report di YouTube. Il plugin espone le riproduzioni del feed e le engaged views disponibili nelle API pubbliche e le presenta con la loro definizione. [Guida YouTube Analytics per Shorts](https://support.google.com/youtube/answer/12942217) · [Posizioni di riproduzione e modifica delle views](https://developers.google.com/youtube/analytics/revision_history)

> Per questo Short mostrami engaged views, riproduzioni nel player Shorts, retention nei primi 3 e 10 secondi, segmenti con più abbandoni, sorgenti di traffico e RPM stimato se autorizzato. Distingui i dati mancanti e indicami l'ultima data osservata.

> Negli ultimi 28 giorni, quali sorgenti e dispositivi hanno generato più impressioni delle miniature? Incrocia il CTR segmentato con i dati di traffico e con la retention dei video che si discostano dal benchmark.

## Benchmark, interventi e rivalutazioni

L'analisi dettagliata seleziona fino a otto comparabili per formato, durata, argomento, serie, recenza e stagionalità. Quando il campione lo consente allinea la sorgente prevalente. Mostra campioni e percentili per ciascun dato. La graduatoria massiva usa soprattutto volume delle impressioni, scostamento di CTR, campione e interventi recenti. L’andamento delle visualizzazioni recenti aggiunge contesto e modula la priorità quando entrambe le settimane sono osservabili. Una riga prioritaria è una richiesta di approfondimento. Prima di proporre un intervento leggi titolo, miniatura, andamento, reach, retention e sorgenti.

`chewbecca_prepare_video_optimization` salva un solo intervento per bozza. Per le miniature di video lunghi usa `chewbecca_optimization_context` per i sottotitoli eventualmente scaricabili e lo stile di Chewbecca, e allega tre proposte generate con gli strumenti immagine di ChatGPT. Usa `riferimenti-stile/` se servono i file di esempio. Per una bozza applicata la miniatura nuova è identificata dall'impronta dell'immagine selezionata. Il vecchio URL può servire una versione successiva per effetto del caching YouTube. L'azione su titolo o descrizione rilegge lo snippet prima della scrittura e invia solo quello, conservando gli altri campi scrivibili dello snippet. Se il campo da modificare è cambiato dopo la bozza, serve una nuova anteprima.

Le finestre post-intervento usano 7, 14 o 28 giorni prima e dopo, escludendo il giorno della modifica. Un campione incompleto o senza reach viene segnalato. Le variazioni sono osservazioni e non controllano contemporaneamente tutti i fattori. Gli interventi sugli Shorts supportano titolo e descrizione. L'upload di una custom thumbnail tramite questo flusso è limitato ai video lunghi.

Il backend produce dati e proposte salvate, con report periodico opzionale. Non invoca ChatGPT Plus in autonomia. Le eventuali esecuzioni periodiche di ChatGPT dipendono dalle capacità di Tasks e dall'accesso al plugin nella relativa sessione. L'email opzionale contiene il riepilogo deterministico senza commenti grezzi o risposte proposte. Su Actuator usa `/actuator/health` e, con accesso admin, `/actuator/metrics` per le metriche `chewbecca.community.*` e `chewbecca.performance.*`.

## Prompt per Chewbecca Studio

> Analizza tutti i commenti ricevuti questa settimana sul mio canale, anche su video vecchi e Shorts. Scorri tutte le pagine, segnala eventuali limiti di copertura e prepara risposte nello stile del canale. Distingui Canon e Legends. Mostrami tabella, priorità e segnalazioni. Attendi la mia approvazione.

> Approva soltanto le righe 1, 3 e 7 di questo batch. Escludi la 8 e riscrivimi la 4 prima di approvarla. Mostrami la revisione aggiornata e attendi una conferma separata per pubblicare.

> Analizza questo video usando le finestre comparabili già complete. Mostrami impressioni, CTR, retention nei primi 30 e 60 secondi, provenienza del traffico e divario dal benchmark. Se proponi cambiamenti, prepara bozze separate che posso approvare singolarmente.

> Analizza i video lunghi pubblicati negli ultimi tre mesi. Scorri l'audit e approfondisci le cinque opportunità con più evidenza. Se proponi miniature, usa i riferimenti Chewbecca e mostrami tre alternative per video.

> Prepara il report settimanale Chewbecca. Completa il censimento commenti se il campione del report è parziale, individua le opportunità con dati sufficienti e verifica gli interventi maturi a 7, 14 e 28 giorni. Mostrami ogni proposta prima di pubblicare o modificare.
