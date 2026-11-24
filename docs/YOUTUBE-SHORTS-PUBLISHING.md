# Shorts: analisi ultimi 2 mesi e proposta nelle prossime 48 ore

## Cosa e' stato aggiunto

- **GET `/admin/youtube/shorts/recommendation`**: considera solo gli Shorts pubblici degli **ultimi due mesi** con una prima settimana di dati completa e propone uno slot tra **ora+2h e ora+48h** (fuso Europe/Rome), con al massimo tre alternative.
- **POST `/admin/youtube/shorts/drafts`**: genera una bozza di titolo e descrizione con la chiave OpenAI API gia' configurata nel progetto; il testo si basa sul *riassunto o trascrizione* forniti (non e' ancora implementata la trascrizione automatica di un MP4).
- **GET `/admin/youtube/shorts/drafts/{id}`**: legge la bozza e verifica lo stato.
- **POST `/admin/youtube/shorts/drafts/{id}/schedule`**: soltanto dopo una richiesta esplicita dell'amministratore carica l'MP4 su YouTube e lo programma come `private` con `status.publishAt`.

Tutti i nuovi endpoint sono sotto `/admin/**` e richiedono il **Basic Auth admin** esistente. La chiave `GPT_ACTION_API_KEY`, usata nelle API di sola analisi, **non** autorizza la pubblicazione. Non abbiamo registrato azioni di scrittura nello schema pubblico del GPT.

## Metodologia statistica (non predizione garantita)

1. Cerca nel catalogo Shorts pubblici con data di pubblicazione da **oggi - 2 mesi** a oggi; scarta gli ultimi video se mancano sette giornate complete di metriche. Limitazione esplicita: al massimo 100 video (risposta `historyTruncated`). La distinzione Shorts usa il classificatore esistente nel repository, che puo' ricorrere a una classificazione per durata.
2. Per ciascuno, interroga Analytics per **views nei primi sette giorni di calendario**, cosi' un video di 60 giorni non domina uno di 14 soltanto perche' ha accumulato piu' visualizzazioni. Non sono esattamente sette periodi mobili di 24 ore: l'API consente aggregati giornalieri.
3. Calcola un punteggio per ogni slot di 30 minuti dalle 10 alle 21:30 (esclusi gli slot gia' passati) nei prossimi **due giorni / 48 ore**, pesando il giorno della settimana, la vicinanza oraria e le visualizzazioni logaritmiche. Riduci la confidenza quando le osservazioni sono poche (shrinkage).
4. Restituisce il migliore e fino a due alternative distanti almeno due ore; indica il numero di video, confidenza bassa/media e limiti del metodo.

**Limite essenziale:** YouTube Analytics API non espone la heatmap oraria degli spettatori disponibili in YouTube Studio. Questa e' una **euristica correlativa** basata sull'orario di pubblicazione storico. Non e' una misura diretta degli spettatori online, non dimostra causalita' e potrebbe essere influenzata da argomenti, giorno di lancio, promozione e differenze di algoritmo.

## Preparazione del collegamento Google

1. Abilita **YouTube Data API v3** in Google Cloud, se non lo hai gia' fatto.
2. Esegui il deploy della nuova versione con le variabili gia' esistenti (`GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `TOKEN_ENCRYPTION_KEY`, `OPENAI_API_KEY` e configurazione admin).
3. Visita `GET /admin/youtube/connect`, autenticandoti come admin, e approva il nuovo scope Google `https://www.googleapis.com/auth/youtube.upload`.
4. Verifica che l'account Google autorizzato sia il canale previsto prima di caricare video reali.

Le quote API e le condizioni di verifica/audit Google possono influire sulla disponibilita' della pubblicazione automatica. Verifica almeno un caricamento di test su YouTube Studio prima di usare il flusso con video importanti.

## Esempi HTTP (usa variabili locali, non inserire credenziali nei comandi condivisi)

Recupera la raccomandazione:

```sh
curl -u "$ADMIN_USERNAME:$ADMIN_PASSWORD" \
  'https://TUO-SERVIZIO.onrender.com/admin/youtube/shorts/recommendation'
```

Crea la bozza dal contenuto reale del video:

```sh
curl -u "$ADMIN_USERNAME:$ADMIN_PASSWORD" \
  -H 'Content-Type: application/json' \
  -d '{"brief":"Questo breve video racconta come i cloni hanno eseguito l Ordine 66 e quali Jedi sono riusciti a fuggire, con esempi tratti dal nostro montaggio.","madeForKids":false}' \
  'https://TUO-SERVIZIO.onrender.com/admin/youtube/shorts/drafts'
```

Il JSON restituisce `id`, `title`, `description`, `publishAt`, `status=DRAFT`. **Rivedi sempre il testo generato.** Il campo `madeForKids` deve corrispondere alla reale destinazione del contenuto secondo le regole di YouTube.

Conferma e carica il file (l'orario `publishAt` e' facoltativo: altrimenti usa quello salvato nella bozza; deve essere tra 1 ora e 48 ore da adesso):

```sh
curl -u "$ADMIN_USERNAME:$ADMIN_PASSWORD" \
  -F 'video=@/percorso/reel.mp4;type=video/mp4' \
  -F 'title=Il vero segreto dell Ordine 66' \
  -F 'description=Una storia dalla galassia di Star Wars. #StarWars #Shorts' \
  -F 'publishAt=2026-11-25T17:30:00Z' \
  'https://TUO-SERVIZIO.onrender.com/admin/youtube/shorts/drafts/ID-BOZZA/schedule'
```

Se il caricamento ha un esito incerto, la bozza passa a `UNKNOWN`: **non ripetere automaticamente l'operazione**, controlla prima YouTube Studio per evitare duplicazioni. Quando YouTube conferma la programmazione, passa a `SCHEDULED` e restituisce il link del video.

## Limitazioni dell'MVP

- Nessuna pubblicazione reale effettuata durante lo sviluppo: serve un canale Google collegato e il deploy effettivo.
- Non e' stata creata una UI chat; la gestione avviene via REST amministrativa (integrabile con una futura chat).
- Non viene ancora trascritto o analizzato autonomamente il file MP4: serve un brief/trascrizione per titolo e descrizione pertinenti.
- Solo MP4 fino a 300 MB; il classificatore finale degli Shorts dipende da YouTube e dal rapporto d'aspetto/durata del file.
- Upload su disco **temporaneo**, nessun salvataggio permanente di file video; un upload ripristinabile non e' ancora implementato al livello dei chunk, il caricamento usa una sessione resumable ufficiale con un solo PUT.
- Non ci sono ancora retry/manual reconciliation/thumbnail custom o controllo approfondito della codifica. Usa inizialmente file di prova piccoli.
- Il comportamento su Render, disponibilita' delle API e verifica Google sono da convalidare nell'ambiente reale.
