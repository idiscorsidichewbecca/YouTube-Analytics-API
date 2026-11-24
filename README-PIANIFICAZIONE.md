# Chewbecca Studio — pianificazione settimanale degli Shorts

Questa patch si applica a `YouTube-Analytics-API` sul commit `946b70e`. Copia nella root del progetto le cartelle `src` e `docs` presenti nello ZIP. In alternativa, estrai `MODIFICHE.patch` ed esegui `git apply --check MODIFICHE.patch` e `git apply MODIFICHE.patch` dalla root. Usa uno dei due metodi.

La migrazione Flyway V5 aggiunge alle bozze il campo della modalità settimanale. Il valore iniziale delle bozze già esistenti è falso.

## Flusso in ChatGPT

1. Allega lo Short e fornisci una trascrizione o un riassunto. Chiedi di programmarlo nella settimana corrente con la playlist «Lo sapevi?».
2. `chewbecca_plan_weekly_short` propone gli orari in ora italiana. Confronta i primi sette giorni degli Shorts maturi dei quattro mesi precedenti. Usa copertura (visualizzazioni totali e coinvolte), percentuale media vista e interazioni (like, commenti, condivisioni e iscritti ottenuti). Mostra quanti Shorts ha analizzato e alcuni esempi storici. Lo storico è limitato ai 150 Shorts maturi più recenti e la risposta segnala un eventuale troncamento.
3. ChatGPT usa il contesto per proporre un titolo fedele al video, massimo 100 caratteri, e una descrizione con hashtag pertinenti. `chewbecca_prepare_short` memorizza ora, testo e impostazione sul pubblico con `weekly_plan=true`. `made_for_kids=false` indica che il contenuto non è destinato specificamente ai bambini.
4. Dopo la conferma `CONFERMO <draftId>`, il backend ricontrolla la disponibilità su YouTube. La distanza minima è di tre ore da video, Shorts e altre pubblicazioni già programmate. Se non ci sono slot idonei, il piano termina senza scegliere un orario fuori settimana.
5. Lo Short viene programmato e aggiunto alla playlist esistente «Lo sapevi?». Se YouTube conferma la programmazione ma la playlist restituisce un errore, `chewbecca_repair_short_playlist` usa l’ID del video salvato per completare soltanto la playlist, previa conferma `CONFERMO PLAYLIST <draftId>`.

Dopo il deploy, apri `/admin/youtube/connect` per concedere una volta il permesso Google `youtube.force-ssl` necessario per la playlist. `/admin/youtube/status` espone il campo `playlistManagementGranted`. La pianificazione richiede lo strumento MCP `chewbecca_plan_weekly_short` nell'app ChatGPT.

Verifica locale con Java 21 e Maven. Build riuscita e 58 test passati. Avvio locale verificato con Flyway V5, health UP e i cinque strumenti MCP registrati. L’archivio contiene il codice da distribuire.
