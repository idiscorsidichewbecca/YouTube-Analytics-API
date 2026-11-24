# Chewbecca: plugin privato in ChatGPT Sites (Plus) — guida al primo collegamento

## Architettura e limiti

Il codice Spring Boot aggiunge **API REST server-to-server**, non un server MCP autonomo.
ChatGPT Sites ospiterà il server MCP del plugin privato. Gli strumenti del Sito chiameranno il backend su Render usando una chiave dedicata memorizzata **solo nei segreti server-side del Sito**.

ChatGPT Plus e Sites supportano plugin ospitati sul proprio Sito, **ma la disponibilità finale dipende dall'accesso dell'account a Sites e ai permessi per creare plugin MCP**. Non è stata eseguita alcuna installazione del plugin nel tuo account.

Per l'uso con account che non controlli personalmente occorre **OAuth 2.1** per il server MCP con autorizzazione per utente; la semplice chiave server-to-server di questa prima versione NON costituisce un sistema multi-utente. Il Sito/plugin deve rimanere **privato al solo proprietario**.

## 1. Deploy backend Spring Boot su Render

1. Esegui backup DB, compila `./mvnw clean verify` e testa in staging.
2. Su Render aggiungi **CHATGPT_BRIDGE_ENABLED=true**.
3. Genera una chiave indipendente dalla GPT Action API key (ad esempio `openssl rand -hex 32`) e impostala come `CHATGPT_BRIDGE_API_KEY`. Mai incollarla nei messaggi di ChatGPT e mai inviarla al browser.
4. Mantieni `GPT_ACTION_API_KEY` separata. La chiave GPT di analisi **NON** può usare `/internal/chatgpt/**`.
5. Configura `CHATGPT_FILE_ALLOWED_HOSTS` con hostname esatti **effettivamente usati** negli `download_url` prodotti da ChatGPT. Default `files.oaiusercontent.com,files.openai.com`. NON usare wildcard, né host di cui non ti fidi. Se la URL proviene da un hostname diverso, verifica personalmente che sia un dominio del servizio prima di aggiungerlo.
6. Conserva `ADMIN_USERNAME`, `ADMIN_PASSWORD`, `TOKEN_ENCRYPTION_KEY` e i token Google SOLO su Render, non sul Sito.
7. Verifica che in Google OAuth il consenso includa `youtube.upload` e che il canale Google corrisponda al canale atteso. Per programmazione, Google deve accettare `privacyStatus=private` e `publishAt` futuro. Testa in ambiente reale.

### Test endpoint senza rivelare le credenziali

```bash
export BACKEND='https://TUO-SERVIZIO.onrender.com'
export CHATGPT_BRIDGE_API_KEY='...' # solo terminale locale, non committare
curl -i -H "Authorization: Bearer $CHATGPT_BRIDGE_API_KEY"   "$BACKEND/internal/chatgpt/shorts/recommendation"
```

- Con `CHATGPT_BRIDGE_ENABLED=false`, ritorna 404.
- Con chiave sbagliata, ritorna 401.
- Con chiave valida, torna una raccomandazione oppure un errore di Analytics a cui dare diagnosi.

## 2. ChatGPT Sites: creare il plugin

1. Apri ChatGPT Sites in un account Plus che permette la creazione di plugin.
2. Crea un Sito chiamato **Chewbecca Studio**. Mantieni accesso privato al proprietario, NON distribuire il plugin a persone esterne.
3. Usa il prompt riportato in `docs/SITES-PLUGIN-PROMPT.txt` e il modello di strumenti `docs/sites-mcp-tools.ts` come riferimento. Chiedi al builder del Sito di usare il suo framework MCP/server supportato e di collegare i 4 strumenti.
4. Negli **Settings > Secrets** del Sito imposta `CHEWBECCA_BACKEND_URL=https://TUO-SERVIZIO.onrender.com` e `CHEWBECCA_PLUGIN_KEY` con lo STESSO valore di `CHATGPT_BRIDGE_API_KEY` di Render. I segreti devono essere accessibili solo al server, mai alla pagina pubblica o alle risposte degli strumenti.
5. Pubblica il Sito, installa il plugin personale generato e verifica che siano presenti i quattro strumenti (`analyze`, `prepare`, `get_status`, `schedule`).
6. **Non** incollare mai le credenziali Google o Render in una conversazione; usa l'interfaccia Segreti.

## 3. Flusso in chat

1. L'utente allega l'MP4 e chiede `analizza i due mesi precedenti e proponimi la data migliore nelle prossime 48 ore`.
2. Tool `chewbecca_recommend_slot`: `GET /internal/chatgpt/shorts/recommendation`.
3. ChatGPT esamina davvero il video, **se** le capacità della chat lo permettono. Se non può interpretare audio/fotogrammi, chiede una trascrizione o un brief; non inventa dettagli.
4. ChatGPT crea titolo e descrizione, propone orari/alternative. L'utente approva il testo, poi tool `chewbecca_prepare_short`: `POST /internal/chatgpt/shorts/drafts` con `brief`, `title`, `description`, `madeForKids`.
5. Dopo la bozza, l'utente deve scrivere esplicitamente **`CONFERMO <id-bozza>`**. È un ulteriore controllo procedurale, NON una prova crittografica che una persona abbia autorizzato l'upload. Il tool di programmazione dev'essere marcato come azione di scrittura e soggetto alla conferma della piattaforma.
6. Tool `chewbecca_schedule_short` passa il riferimento temporaneo al file tramite `_meta["openai/fileParams"]` e chiama `POST /internal/chatgpt/shorts/drafts/{id}/schedule`.
7. Il backend scarica il file da HTTPS con allowlist host rigorosa, limita a 300MB, controlla la firma MP4, poi carica su YouTube in privato con `publishAt`. Non salva il file permanentemente.
8. Mostra URL del video e stato `SCHEDULED`; se la richiesta fallisce dopo aver iniziato l'upload **NON ritentare automaticamente**. Consulta lo stato della bozza e YouTube Studio per verificare eventuali duplicati.

### Endpoint REST disponibili (NON MCP)

| Funzione | REST |
|---|---|
| Raccomandazione | `GET /internal/chatgpt/shorts/recommendation` |
| Crea bozza da titolo/descrizione scritti in ChatGPT | `POST /internal/chatgpt/shorts/drafts` |
| Controlla stato | `GET /internal/chatgpt/shorts/drafts/{id}` |
| Pubblica dopo conferma | `POST /internal/chatgpt/shorts/drafts/{id}/schedule` |

### Schemi JSON

**Crea bozza:**

```json
{"brief":"Spiegazione/trascrizione reale del video in almeno trenta caratteri.","title":"Titolo efficace ma fedele al contenuto","description":"Descrizione pertinente e hashtag","madeForKids":false}
```

**Programma (dopo conferma dell'utente):**

```json
{
  "file":{"download_url":"https://HOST-FILE-CHATGPT/path-temporaneo","file_id":"file_1234567890","mime_type":"video/mp4","file_name":"short.mp4"},
  "confirmationText":"CONFERMO <id-bozza>",
  "title":null,
  "description":null,
  "publishAt":null
}
```

Se `publishAt` è omesso usa lo slot calcolato al momento della bozza. Il backend verifica ancora che sia compreso tra +1h e +48h; se la finestra è passata, crea una nuova bozza o specifica una data valida.

## 4. Limitazioni da conoscere prima di usarlo

- **Nessuna pubblicazione è stata effettuata in questa consegna.** Non è stato collegato un account ChatGPT o Google a questa esecuzione.
- Il backend **non** fa trascrizione automatica: se ChatGPT non legge bene il video, serve un brief o trascrizione. La generazione di titolo/descrizione nella chat non necessita di una OpenAI API key aggiuntiva.
- L'algoritmo statistico usa i **due mesi storici** (finestra metrica fissa di primi 7 giorni), poi sceglie uno slot nei **due giorni successivi**. È una stima, non il dato 'spettatori online' di YouTube Studio.
- Il download_url scade: allegare un file non significa che quel link resterà utilizzabile per giorni. Meglio confermare/caricare nella stessa sessione.
- **FileParams** è supportato dal protocollo di plugin, ma il trasferimento effettivo nel tuo Sito/account deve essere verificato dal primo test.
- Spring Boot REST usa autenticazione service-to-service; il codice del Sito deve garantire accesso owner-only. Se intendi rendere il plugin pubblico o usarlo in gruppo, NON utilizzare questa modalità: implementa OAuth 2.1 e privilegi per-utente.
- Alcune tipologie di contenuti/video possono essere limitate dall'API o dalle regole YouTube (quota, diritti, verifica del progetto Google, visibilità Shorts).
- Su Render il filesystem effimero e la durata delle richieste vanno verificati; gli upload grandi potrebbero richiedere chunk e retry manuale.

## Fonti ufficiali

- https://help.openai.com/en/articles/20001547-hosting-a-plugin-with-chatgpt-sites
- https://help.openai.com/en/articles/20001339-creating-and-using-chatgpt-sites
- https://developers.openai.com/plugins/reference — `openai/fileParams`
- https://developers.openai.com/plugins/build/auth — OAuth2.1 per MCP se esposto a più utenti
- https://developers.google.com/youtube/data/reference/rest/v3/videos/insert
