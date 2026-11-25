# Chewbecca Studio — Star Wars Expert Quiz (Meta API ufficiale)

Correzione del modulo cumulativo, da integrare sulla base completa Git d8de6668a9dd1b9990f34b23c83aff95b1ef84b8 tramite lo script di ricostruzione. Nessun Python o API privata. La build integrata deve essere eseguita dopo la ricostruzione.

## 1. Collegare Meta — OAuth già esistente

Il modulo riusa Facebook Login for Business, `MetaAccessTokenService`, `MetaGraphClient` e la credenziale cifrata già presente in PostgreSQL. Il client usa `graph.facebook.com`, `META_GRAPH_API_VERSION`, token Pagina e `appsecret_proof`. Non sono necessari un secondo account, un secondo OAuth o un token separato. Il token scaduto richiede riconnessione tramite `/admin/meta/connect` (nessun nuovo rinnovo automatico implementato).

Per pubblicare storie occorre un account Instagram Business idoneo collegato alla Pagina e il permesso `instagram_content_publish` concesso alla configurazione Meta Login for Business esistente. Per lettura e Insights verificare `instagram_basic`, `pages_read_engagement`, `instagram_manage_insights` ed eventuali ulteriori requisiti/approvazioni Meta. Se manca il permesso di pubblicazione, modificare la configurazione associata a `META_CONFIG_ID`, ripetere `/admin/meta/connect` e verificare `/admin/meta/status` e `chewbecca_instagram_connection_status` (`publishPermission`). La presenza dello scope non garantisce che Meta autorizzi ogni asset: serve collaudo reale. Nessuna pubblicazione di prova automatica.

Configurazioni Render (conservare quelle Meta e MCP esistenti):

```
META_ENABLED=true
APP_INSTAGRAM_ENABLED=true
APP_INSTAGRAM_LIVE_ENABLED=false
APP_INSTAGRAM_PUBLIC_URL=https://youtube-analytics-api.onrender.com
```

`APP_INSTAGRAM_PUBLIC_URL` ha fallback a `MCP_PUBLIC_URL`. Non servono `APP_INSTAGRAM_ACCOUNT_ID`, `APP_INSTAGRAM_ACCESS_TOKEN`, `APP_INSTAGRAM_GRAPH_VERSION`. In modalità non live si possono preparare bozze, non programmare o pubblicare. Attivare live solo dopo test e approvazione. Nessun Python, API privata o password Instagram. Usare un servizio Render sempre attivo e PostgreSQL; verificare V8, build Maven e registrazione effettiva MCP prima del rilascio. Gli scope MCP `shorts:read` e `shorts:write` restano quelli già presenti; non sono permessi Meta.

Documentazione: https://developers.facebook.com/docs/instagram-platform/instagram-api-with-facebook-login/content-publishing/

## 2. Flusso editoriale batch

1. `chewbecca_quiz_context` e `chewbecca_quiz_history` (paginare): ChatGPT recupera stile e storico.
2. `chewbecca_prepare_quiz_batch`: da 1 a 20 quiz, default intervallo 5 giorni, data iniziale futura scelta da ChatGPT in ora italiana. Ogni quiz contiene testo, soluzione, fonte e CANON/LEGENDS. Una domanda aperta estremamente difficile, non trivia comuni. Il modello deve ricercare le fonti prima di proporre. Il backend controlla duplicati testuali normalizzati con vincolo univoco; le ripetizioni semantiche e la qualità/fondatezza sono controlli editoriali del modello e dell'utente, non verifiche automatiche garantite. Il calendario riserva un solo appuntamento per giorno (anche se annullato: riutilizzo della data non implementato).
3. ChatGPT genera **due immagini definitive separate** per quiz, verticali 9:16. Stile #FED000, margini di sicurezza, fonte leggibile sulla soluzione, nessuno spoiler sulla domanda. `chewbecca_stage_quiz_graphics` riceve due allegati reali, normalizza JPEG 1080x1920 e restituisce anteprime delle immagini salvate. Se la generazione in chat non espone gli ID dei file al plugin, allegare i file generati nella stessa chat. Nessun LLM o generatore immagini nel backend. La registrazione delle coppie è per posizione per compatibilità fileParams; la preparazione e l'approvazione sono in batch. Correggere grafiche con una nuova chiamata stage prima di approvare. Per modificare i testi o le date, annullare e creare nuovo batch (la data rimane riservata e le domande storiche non sono riutilizzabili in questa versione).
4. `chewbecca_quiz_batch_status` mostra stato, date, orari, fonti e revisione. `chewbecca_preview_quiz_graphics` consente di rivedere le immagini salvate; `chewbecca_list_quiz_batches` consente di recuperare batch in altre chat. Mostrare tutte le anteprime prima di chiedere `CONFERMO QUIZ <batch_id> <revision>` (la revisione cambia per ogni sostituzione). `chewbecca_schedule_quiz_batch` verifica immagini e conferma, poi programma tutte le coppie in una transazione. Non carica ancora su Meta.
5. Scheduler ogni 30 secondi (configurabile) crea container solo quando necessario, attende FINISHED e pubblica. La risposta richiede la domanda PUBLISHED. Esito incerto UNKNOWN: nessun retry automatico, controllare Instagram; riconciliazione manuale non automatizzata. Se l'app è rimasta spenta oltre 1h dalla data pianificata, la storia è MISSED. `chewbecca_cancel_quiz_batch` annulla le storie non ancora avviate, non cancella storie pubblicate. Batch status indica stato dell'approvazione; i singoli status indicano gli esiti di pubblicazione.

## 3. Fasce migliori

Per ogni tipo separatamente, acquisisce reach dalle storie pubblicate da **questo modulo** a 20–21 ore dalla pubblicazione (poll ogni 5 minuti), usa fino a 120 giorni di osservazioni. Non considera dati mancanti o -1 uguali a zero. Candidati domanda 08–11, soluzione 18–22; mediane per ora italiana, almeno 3 osservazioni per ciascuna di almeno due fasce prima di scegliere la mediana maggiore. HIGH_SAMPLE (8+), MEDIUM_SAMPLE (3+), LOW_SAMPLE (<3) indicano solo dimensione del campione, non certezza statistica. Quando non ci sono dati comparabili esplora fasce meno campionate, partendo da 09/20 e distribuendo il batch. Non equivale alla heatmap ufficiale dei follower, non garantisce performance future e non importa le storie pregresse. I calendari approvati restano fissi: i dati successivi migliorano i nuovi batch. Se Meta non fornisce reach in tempo utile, resta in modalità esplorativa.

## 4. Sicurezza e persistenza

Immagini su PostgreSQL (base64 in TEXT), senza dipendenza da filesystem effimero o bucket. Conservazione delle grafiche pubblicate per revisione; valutare spazio e retention. Route HTTPS `/instagram-media/{key}` permette al crawler Meta di leggere solo il JPEG della storia in stato PUBLISHING, con capability casuale non esposta a ChatGPT. Non registrare percorso completo di questa route in access log, proxy, tracing; chi possiede l'URL durante la pubblicazione può leggere l'immagine. SecurityFilterChain dedicata alla sola route; verificare precedenza e altri filtri nel progetto completo. Lo scope MCP write è richiesto anche per modificare bozze; la conferma è associata a revisione immutabile dopo approvazione. Claim DB evita l'invio concorrente; un crash può lasciare UNKNOWN dopo la finestra di recupero. Nessun retry cieco. La data di fine batch può superare la validità del token: rinnovarlo prima della scadenza e mantenere il servizio attivo.

## 5. Prompt consigliato

@Chewbecca Studio, prepara N appuntamenti Star Wars Expert Quiz, uno ogni 5 giorni. Domande estremamente difficili per veri conoscitori, non a scelta multipla, su particolari poco noti di Canon o Legends (indicalo chiaramente). Verifica ogni risposta con fonte precisa, preferibilmente originale. Consulta tutto lo storico per evitare ripetizioni anche semantiche. Scegli per ciascun appuntamento la fascia mattutina e serale usando gli Insights restituiti dal backend; se i dati non bastano dichiara le fasce esplorative. Genera due grafiche verticali definitive per quiz: domanda al mattino, risposta con spiegazione e fonte alla sera. Registra le coppie e mostrami immagini, calendario, fonte ed evidenze. Non pubblicare e non programmare fino a quando non confermo la revisione completa del batch.

## 6. Verifiche

`sh verifiche-instagram/run-smoke.sh`: controlli Java 21 autonomi su algoritmo orari, dati mancanti, normalizzazione e rifiuto immagini orizzontali. Test JUnit aggiuntivi inclusi, da eseguire nel progetto completo. Build Maven, migrazione reale, registrazione tool, trasferimento immagini generate e pubblicazione/Insights Meta non ancora verificati. Nessuna modifica a GitHub o Render. Nessuno sticker interattivo o link (API ufficiali).

## 7. Priorità al calendario YouTube (video e Shorts)

Ogni data italiana in cui risulta una pubblicazione YouTube protegge l'intervallo locale `[17:00, mezzanotte)` indipendentemente dall'orario di uscita. Ciascuna storia occupa `[pubblicazione, pubblicazione + 24 ore effettive)`, non semplicemente il giorno di pubblicazione; la verifica usa `Instant` e `Europe/Rome` (anche cambio ora). Esempio: video mercoledì alle 17, storia martedì alle 19 resta attiva mercoledì fino alle 19: vietata. La storia di martedì mattina potrebbe essere compatibile da sola, ma l'appuntamento è indivisibile: si spostano insieme domanda e risposta. Il giorno successivo all'uscita è utilizzabile se non confligge con altre uscite.

Il backend usa il canale Google già autorizzato, la playlist uploads paginata e `videos.list`: `status.publishAt` per i video programmati (inclusi privati), `snippet.publishedAt` per i pubblicati pubblici; include prudenzialmente anche i non in elenco, la cui data può essere quella di upload. Privati senza programmazione esclusi. Non usa una classificazione Short/video. Metadati incompleti, account non disponibile, quota o errori Google bloccano la preparazione/conferma/pubblicazione: non si presume mai calendario vuoto. Documentazione: https://developers.google.com/youtube/data/reference/rest/v3/videos e https://developers.google.com/youtube/data/reference/rest/v3/playlistItems . Il controllo legge tutto il catalogo del canale, quindi va valutato il consumo quota sul catalogo reale.

- Preparazione: usa gli orari più favorevoli/esplorativi già selezionati e cerca il primo giorno compatibile entro 180 giorni; salta anche giorni già riservati. Intervallo richiesto (default 5 giorni) diventa **minimo** tra appuntamenti, non cadenza rigida. Mostra `requestedDay`, `shiftedForCalendarOrAvailability` e data effettiva nelle evidenze. Se manca anche una sola coppia, transazione rollback e batch non creato.
- Conferma: nuovo snapshot Google, tutte le coppie ricontrollate. Conflitto = batch ancora DRAFT, nessuna programmazione; non modifica date o immagini già mostrate. Questa versione non ha un tool di ripianificazione della stessa bozza: annullare e preparare nuovo batch con domande non già riservate, secondo le limitazioni del modulo.
- Worker: snapshot prima di creare il container e di nuovo immediatamente prima di `media_publish`, controllando l'orario effettivo e, per la domanda, anche l'orario pianificato della risposta. Conflitto = `BLOCKED / YOUTUBE_EVENING_CONFLICT_YYYY-MM-DD`; dati Google indisponibili = `BLOCKED / YOUTUBE_CALENDAR_UNAVAILABLE`. Non pubblica, non sposta e non riprova automaticamente; le risposte di domande bloccate seguono la regola preesistente. Controllo puntuale, non lock distribuito con YouTube: una nuova uscita programmata dopo il controllo o dopo la pubblicazione Instagram può ancora creare sovrapposizione, non viene cancellata una storia già online. Le bozze YouTube non ancora caricate o non ancora programmate su YouTube non sono visibili. Non modifica i tool di pubblicazione YouTube.

`sh verifiche-instagram/run-smoke.sh` include 17 controlli calendario autonomi (limiti, video/Short, slittamenti, giorni riservati, DST) oltre agli 8 preesistenti. I controlli del servizio e worker richiedono la build/test sul repository Maven completo e l'account Google reale.
