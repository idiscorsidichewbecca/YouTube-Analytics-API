# Chewbecca Studio — estensione Instagram (PoC)

Questa è una **patch cumulativa**, non un progetto Maven autonomo. Conserva tutti i file dell'archivio Copertine-YouTube, inclusi `MODIFICHE.patch` e riferimenti originali. Aggiunge due classi Java e il microservizio `instagram/`. Se le copertine sono già applicate, aggiungere solo i nuovi file (oppure `git apply MODIFICHE-INSTAGRAM.patch` dalla radice del repository). Se non lo sono, applicare prima i file o la patch originale delle copertine, poi la patch Instagram. Non applicare entrambe le forme della stessa modifica. Non copiare i segreti nel repository.

## Cosa fa

- LINK: una storia foto 9:16 con sticker verso un URL HTTPS YouTube.
- POLL: una storia foto con un sondaggio nativo richiesto all'SDK (2–4 opzioni, da verificare realmente nell'app).
- PLAIN: una storia foto senza sticker, ad esempio le soluzioni.
- Bozze persistenti, status, annullamento e pubblicazione immediata o programmata. Scheduler ogni 10 secondi, UTC nel DB; gli input richiedono offset, usare Europe/Rome (ora legale/solare appropriata).
- Storico quiz, rifiuto duplicati testuali normalizzati (non garantisce equivalenza semantica).
- Le domande, le risposte corrette e le grafiche vengono create e mostrate da ChatGPT, non dall'SDK. Se il generatore non restituisce file accessibili al plugin, allegarle manualmente. Nessun LLM server-side o chiave OpenAI richiesti.

L'API usata è **privata e non ufficiale**. Non c'è garanzia di funzionamento degli sticker, né di stabilità o conformità ai termini della piattaforma; rischio restrizioni account. Nessun login automatico in servizio, bypass challenge o retry applicativo di pubblicazioni. Utilizzare soltanto un account secondario di propria titolarità per il PoC. `PUBLISHED` significa che l'SDK ha restituito un ID; `MANUAL_CHECK_REQUIRED` significa che lo sticker resta da verificare da un altro account.

## Tool MCP (sei nuovi, con feature flag)

| Tool | Funzione |
|---|---|
| `chewbecca_prepare_instagram_story` | Prepara bozza da allegato JPG/PNG, LINK/POLL/PLAIN |
| `chewbecca_instagram_story_status` | Stato di una bozza |
| `chewbecca_publish_instagram_story` | Pubblicazione immediata confermata |
| `chewbecca_schedule_instagram_story` | Programmazione confermata con data e offset |
| `chewbecca_cancel_instagram_story` | Annulla bozza o programmazione |
| `chewbecca_instagram_quiz_history` | Storico domande e stati |

L'integrazione segue lo stesso schema `List<SyncToolSpecification>` delle copertine, riusa `OpenAiFileDownloadService.downloadImage` con la validazione degli host, senza trasmettere URL firmati al servizio Python. Gli scope MCP esistenti `shorts:read`/`shorts:write` sono riutilizzati intenzionalmente per compatibilità con l'Authorization Server non presente nell'archivio; tutte le scritture e le bozze richiedono entrambi. Prima di esporre a più utenti, introdurre scope Instagram distinti e isolamento delle bozze per utente/account. Il servizio è concepito per un solo proprietario e un solo account di test. Non modificato OAuth YouTube. Nessuna pubblicazione eseguita.

## Avvio e deploy Render

1. Aggiungere i file al repository e compilare/testare Java 21 (`./mvnw clean verify`). Verificare l'aggregazione delle liste dei tool da parte del server MCP; l'archivio non contiene l'autoconfigurazione completa. Verificare che il server esistente mostri i tool soltanto quando abilitati.
2. Creare un servizio **Private Service** Docker separato su Render, stesso workspace e regione del backend, Root Directory `instagram`, Dockerfile `Dockerfile`. Usare una sola istanza, un solo worker, piano sempre attivo e disco persistente montato in `/data` scrivibile dall'UID 10001. Nessuna porta pubblica richiesta. Configurare i valori sotto nelle impostazioni del servizio; non sono credenziali reali.

Python:
```
SERVICE_TOKEN=<segreto casuale almeno 32 caratteri>
DATA_DIR=/data
ENABLE_LIVE=false
PORT=10000
```

Java (servizio già esistente):
```
APP_INSTAGRAM_ENABLED=true
APP_INSTAGRAM_URL=http://<host-privato-render>:10000
APP_INSTAGRAM_TOKEN=<stesso segreto SERVICE_TOKEN>
```

L'URL è configurato dal deployer, mai dal modello. HTTP ammesso solo sulla rete privata fidata; fuori da essa usare HTTPS. Lasciare `APP_INSTAGRAM_ENABLED=false` (default) per non registrare i tool. La feature flag non ferma lo scheduler Python: per interrompere le pubblicazioni impostare anche `ENABLE_LIVE=false`. Riconnettere/aggiornare il plugin dopo il deploy e verificare la discovery.

3. Testare una bozza LINK, POLL e PLAIN in dry-run. `ENABLE_LIVE=false` non registra programmazioni attive e non chiama Instagram.
4. Solo per test reale: inizializzare la sessione su una macchina fidata con `python init_session.py` e account secondario. Non inviare password o `session.json` in chat. Trasferire il file tramite un canale amministrativo sicuro nel disco del servizio; proteggere i permessi. Il trasferimento della sessione tra ambienti può richiedere un nuovo login o challenge: fermarsi in caso di problemi, non aggirarli. Abilitare live esplicitamente. Verificare prima uno sticker link e poi un sondaggio, anche cliccando/votando da un altro account. Se non funzionano, fermare il PoC. La sessione, le immagini e SQLite richiedono backup protetto e retention; nessuna pulizia automatica in questa versione.

## Esempio prompt promozione

> @Chewbecca Studio, prepara una storia Instagram 9:16 per il nuovo video, con sticker link nativo che porta esattamente al suo URL YouTube. Lascia libero lo spazio nella parte bassa (centro verticale 76%). Mostrami la grafica, il link e la bozza; attendi la mia conferma CONFERMO STORY <id> prima di pubblicare. Non usare link scritto nella grafica come sostituto.

## Esempio prompt rubrica ogni cinque giorni

> @Chewbecca Studio, consulta chewbecca_instagram_quiz_history e prepara sei appuntamenti Star Wars a intervalli di cinque giorni, con cinque domande diverse a difficoltà crescente e opzioni verificabili. Crea una grafica verticale per ciascuna lasciando libero lo spazio del sondaggio (centro 67% altezza), più una grafica con le soluzioni. Registra ciascuna con chewbecca_prepare_instagram_story. Mostrami le sei bozze per appuntamento e le date in ora italiana; le cinque domande escono a un minuto di distanza, le soluzioni il giorno successivo, circa 23 ore dopo la quinta storia. Aspetta la conferma esplicita di ogni bozza e data prima di chiamare chewbecca_schedule_instagram_story. Non dichiarare che gli sticker funzionano finché non sono stati verificati.

Conferma programmazione (secondi obbligatori, offset valido): `CONFERMO STORY <id> 2026-12-01T10:00:00+01:00`. Pubblicazione immediata: `CONFERMO STORY <id>`. Annullamento: `ANNULLA STORY <id>`.

La rubrica è pianificabile in anticipo (fino a 90 giorni) senza ChatGPT aperto. **Non** è inclusa la generazione autonoma di nuovi quiz, una conferma cumulativa atomica, o un vincolo transazionale fra le cinque domande e le soluzioni: sono programmazioni indipendenti. Verificare il risultato delle cinque domande prima che esca la soluzione e annullarla se necessario. Per evitare ritardi dopo un outage, le pubblicazioni scadute di oltre 15 minuti diventano `MISSED`. `BLOCKED` indica precondizioni mancanti, `UNKNOWN` o `PUBLISHING` necessitano riconciliazione manuale. Non creare una seconda bozza per ripetere uno stato incerto. Non usare più processi/repliche.

## Verifica

Cinque test Python con SDK simulato passati: autorizzazione, validazione, conferma, dry-run, idempotenza, esito incerto, scheduler, annullamento, scadenza, storico e duplicati. `compileall` per Python. Non installato Instagrapi nell'ambiente di test; build Docker, compilazione Java, discovery reale MCP, account Instagram e deploy non verificati. La dipendenza Instagrapi 3.2.8 è documentata; controllare risoluzione del lock/dependency build su Render prima del test. Non è stato alterato nessun file del servizio originale.

Riferimento SDK: https://subzeroid.github.io/instagrapi/3.2.8/usage-guide/story/
