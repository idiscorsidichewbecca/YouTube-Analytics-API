# Chewbecca Studio — Copertine YouTube

Patch per `YouTube-Analytics-API`, a partire dal commit `19af0a6` (rilascio batch Shorts).

## Installazione

Estrai `MODIFICHE.patch` e dalla root del progetto esegui `git apply --check MODIFICHE.patch` e `git apply MODIFICHE.patch`. In alternativa copia soltanto `src/` dallo ZIP. Il deploy esegue la migrazione Flyway V7 per le bozze. Aggiorna l'elenco strumenti di Chewbecca Studio dopo il deploy.

Il preset testuale è in `src/main/resources/copertine/stile-chewbecca.txt`. Le dieci immagini originali sono in `riferimenti-stile/` per poterle riallegare in altre chat. Quella cartella è fuori dalla patch Git. Scegli riferimenti coerenti con il format del video, per esempio un tour di nave, un confronto o un episodio di lore.

## Flusso in ChatGPT

1. `chewbecca_audit_long_thumbnails` esamina gli upload del canale con OAuth del proprietario e restituisce conteggi e risultati paginati. Scarta gli Shorts. Cerca i video lunghi `MISSING` e mostra `UNKNOWN` come da verificare. Include i video già caricati e programmati.
2. `chewbecca_thumbnail_context` legge titolo, descrizione, miniatura corrente, data programmata e, quando disponibili via API, sottotitoli. Preferisce i sottotitoli italiani. Se la storia del video rimane ambigua, usa una scaletta, un SRT o un breve riassunto fornito in chat. I video privati possono rimanere programmati.
3. ChatGPT usa i riferimenti originali e il preset per generare **tre immagini separate** A, B e C. Mantiene la grafica del canale e cambia l'idea visiva di ogni proposta. Il generatore immagini di ChatGPT è il componente che crea le immagini. Il backend gestisce catalogo, video, bozze e pubblicazione.
4. `chewbecca_stage_thumbnail_choices` associa le tre immagini all'ID di un video, le converte in JPEG 1280×720 e restituisce anteprime con lettere e ID bozza. Se la chat non passa direttamente le immagini generate come allegati al plugin, allega in chat i tre JPG o PNG già generati.
5. Scrivi `CONFERMO COPERTINA <ID bozza> <A|B|C>` per quel video. `chewbecca_apply_thumbnail` verifica di nuovo proprietario e stato della miniatura prima di aggiornare YouTube. Se nel frattempo compare una miniatura personalizzata, evita la sostituzione. L'esito include il link al video. Dopo il successo restano in DB la scelta e lo stato, mentre vengono rimosse le tre immagini della bozza.

La scansione distingue `hasCustomThumbnail=false` dal caso in cui YouTube omette la proprietà. Per separare Shorts e video normali usa durata e proporzioni, dove disponibili, oltre alle regole di classificazione già configurate. La lettura dei sottotitoli usa il permesso Google `youtube.force-ssl`. L'update usa `youtube.upload` e richiede l'autorizzazione MCP `shorts:write`.

## Prompt

```text
@Chewbecca Studio, scansiona tutti i video lunghi del mio canale e mostrami quali usano ancora una miniatura automatica. Includi i video già caricati e programmati. Elenca anche le eventuali miniature da verificare.

Per ciascun video senza copertina personalizzata, leggi il contesto e prova a recuperare i sottotitoli. Se il tema resta ambiguo, chiedimi la scaletta. Usa le mie copertine allegate e il preset Chewbecca Studio per creare tre vere immagini A, B e C per video. Mostrami la miniatura attuale e le tre proposte, con titolo e ID bozza. Mantieni il nostro stile e fai in modo che ogni immagine proponga un'idea diversa, fedele a quello che racconto nel video.

Aspetta la mia scelta per ogni video. Applica soltanto le opzioni che confermo con CONFERMO COPERTINA <ID bozza> <A|B|C>. Alla fine mostra link ed esito per ogni copertina confermata.
```

Verifica locale su Java 21. Build riuscita, 71 test passati. Avvio con migrazione V7 e undici strumenti MCP verificati.
