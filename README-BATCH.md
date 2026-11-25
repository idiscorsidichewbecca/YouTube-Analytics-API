# Chewbecca Studio — Shorts in batch

La patch parte dal commit `46108b1`, che contiene la pianificazione per giornate libere e le metriche del canale. Copia `src` e `docs` dallo ZIP nella root del progetto oppure estrai `MODIFICHE.patch` ed esegui dalla root `git apply --check MODIFICHE.patch`, poi `git apply MODIFICHE.patch`. Usa uno dei due metodi.

## Come funziona

- Puoi allegare da 1 a 10 MP4 con nomi distinti. Ogni video riceve titolo, descrizione, destinazione ai bambini e orario. L'ordine che dai a ChatGPT determina l'ordine di uscita.
- Il piano cerca il miglior orario per ciascuna delle date selezionate, usando i quattro mesi di storico. Usa le prime settimane disponibili a partire da quella corrente. Puoi chiedere di partire dal prossimo lunedì.
- Ogni settimana italiana va da lunedì a domenica, per un massimo di tre Shorts, inclusi quelli già pubblicati o programmati. Ogni nuova uscita ha una data libera. I video lunghi occupano la giornata senza consumare posti per gli Shorts. La classificazione degli upload esterni riprende le regole del canale e può considerare Shorts i video brevi di formato incerto. Le forzature di classificazione già presenti hanno precedenza.
- Una sola anteprima raccoglie tutte le posizioni, con file, titolo, descrizione, orario e playlist «Lo sapevi?». Scrivi `CONFERMO BATCH <ID>` per approvare. L'app trasferisce poi un MP4 alla volta e il backend controlla nuovamente il calendario prima di ogni upload.
- Se una posizione si blocca, puoi riprendere con lo stesso ID batch. Le posizioni già programmate restituiscono l'ID YouTube salvato. Gli upload con esito incerto richiedono una verifica. Per un errore limitato alla playlist, usa lo strumento di recupero con l'ID della singola bozza.

Dopo il deploy, aggiorna l'elenco degli strumenti del plugin se non vedi `chewbecca_prepare_shorts_batch` e `chewbecca_schedule_batch_short`. Il flusso usa i permessi esistenti `shorts:write` e `youtube.force-ssl`. La migrazione Flyway V6 aggiunge i riferimenti del batch alle bozze.

Verificato con Java 21 e Maven. Build completata, 65 test passati. Il repository e Render mantengono il rilascio precedente fino all'applicazione di questa patch.

## Prompt da incollare

```text
@Chewbecca Studio, prepara in batch gli Shorts MP4 che ho allegato, nell'ordine in cui te li ho dati. Parti dalla settimana corrente e usa anche le settimane successive se serve. Conta gli Shorts già pubblicati o programmati e rispetta il limite di tre complessivi per settimana da lunedì a domenica, in ora italiana. Usa soltanto giornate senza altre uscite e scegli gli orari migliori secondo lo storico dei miei Shorts.

Per ogni video prepara un titolo breve e fedele al contenuto, una descrizione con hashtag pertinenti, l'impostazione “Non destinato ai bambini” e la playlist “Lo sapevi?”. Se ti manca il contesto di un video, chiedimelo. Mostrami il piano completo prima di caricare qualsiasi file e aspetta che ti scriva CONFERMO BATCH <ID>. Poi verifica il calendario e programma ogni Short. Alla fine mostrami l'esito dei singoli file.
```
