# Chewbecca Studio — Programmazione YouTube Shorts da ChatGPT

Patch per il repository `idiscorsidichewbecca/YouTube-Analytics-API` (base `692afed`, Spring Boot 3.5.16, Spring AI 1.1.8, MCP SDK 0.18.5).

## Installazione

Copia gli otto file alle identiche posizioni nel repository. **NON sostituire altri file e NON modificare pom.xml**. Verifica che il pom corrente contenga `mcp-java-sdk.version=0.18.5` come in `692afed`.

Dal repository:
```
./mvnw test
./mvnw package -DskipTests
```

Se i test/build passano, fai commit e push su `main` e controlla deploy `Live` su Render. Questa patch non è stata compilata dal sistema che l'ha preparata: verificare i test Maven prima del rilascio.

Apri ChatGPT -> Plugin -> Chewbecca Studio -> Aggiorna strumenti, poi riconnetti l'account OAuth per autorizzare `shorts:read` e **`shorts:write`**. Se le impostazioni OAuth avanzate permettono di cambiare gli ambiti, includi entrambi; altrimenti il tool di scrittura restituisce una challenge OAuth `insufficient_scope`.

## Strumenti

- `chewbecca_recommend_slot`: analisi degli Shorts, invariato.
- `chewbecca_prepare_short`: crea bozza con titolo, descrizione, data/ora ISO con offset +01:00/+02:00. Non carica nessun video; input brief/trascrizione da 30 a 10.000 caratteri, title <=100, description <=5000, made_for_kids boolean.
- `chewbecca_schedule_short`: dopo esplicita approvazione (`CONFERMO <draftId>`), prende il file MP4 da ChatGPT con `_meta["openai/fileParams"]`, valida autorizzazione shorts:write, scarica in modo sicuro, invia `videos.insert` e pianifica privato con `status.publishAt`. No retry automatico.

## Esempio conversazione

Utente: "@Chewbecca Studio Programma questo reel il 10 dicembre alle 19:30 ora italiana. Parla di ... [video.mp4]. Proponi titolo e descrizione convincenti, senza clickbait falso." 

ChatGPT: genera titolo e descrizione, chiama `chewbecca_prepare_short` usando `2026-12-10T19:30:00+01:00`, mostra la bozza, il testo `CONFERMO <draftId>` e chiede conferma.

Utente: "CONFERMO <draftId>".

ChatGPT: chiama `chewbecca_schedule_short` passando `draft_id`, `confirmation_text` e `file` allegato. La scrittura è assoggettata a permesso `shorts:write`. Restituisce URL e `SCHEDULED` solo se YouTube conferma.

## Avvertenze

- Il file deve essere **MP4, max 300 MB**. Lo Short effettivo viene classificato da YouTube secondo durata/formato; il backend non transcodifica né ritaglia il reel.
- Senza un breve riassunto/trascrizione, la chat **non può inventare il contenuto** guardando soltanto il filename del video. Non richiede `OPENAI_API_KEY`: il testo viene generato dal modello di ChatGPT con l'informazione fornita dall'utente.
- Programmazione consentita tra 1 ora e 365 giorni da adesso; YouTube richiede video `private` fino al momento della pubblicazione.
- Il backend utilizza il token Google/YouTube già collegato; se manca lo scope `youtube.upload`, bisogna ricollegare Google **al backend** una sola volta.
- Stato `UNKNOWN` significa upload potenzialmente avvenuto: **controlla YouTube Studio e non riprovare** per evitare duplicazioni.
- Lo strumento MCP ha uno schema file con `download_url`, `file_id` obbligatori, `mime_type`, `file_name` opzionali, e richiede `openai/fileParams` per ricevere gli allegati ChatGPT.
- Il codice controlla `SCOPE_shorts:write` anche nel server oltre ai metadati degli strumenti e richiede conferma esplicita.
- Verifica con un breve video di prova, senza pubblicarlo immediatamente; conferma anche in YouTube Studio la programmazione.

## Stato test

Modifiche preparate e test JUnit aggiunti; non eseguite compilazione Maven, chiamata effettiva ChatGPT con file né upload YouTube reale nell'ambiente di preparazione.
