# Chewbecca Studio — fix diagnostica download MP4 per MCP

**Base verificata:** GitHub `idiscorsidichewbecca/YouTube-Analytics-API`, commit `0ab49567e9de9416bbcac600edb0ac370299bdc7` del 24 novembre 2026.

## Contenuto

- `src/main/java/it/chewbecca/youtubeanalytics/chatgpt/OpenAiFileDownloadService.java`: classifica gli errori di file, host, HTTP, redirect, rete, dimensioni e MP4. Log strutturati `MCP_FILE_DOWNLOAD_FAILED`, senza URL temporanei, query string, file ID o token. Mantiene allowlist esatta e redirect vietati.
- `src/main/java/it/chewbecca/youtubeanalytics/mcp/ChewbeccaMcpPublishingTools.java`: risultato MCP esplicito `DOWNLOAD_FAILED ... uploadStarted=false`, descrizione dello strumento che invita a fermarsi anziché usare browser/YouTube Studio; conserva la conferma esplicita dell'utente e lo scope OAuth di scrittura.
- Test JUnit per host consentiti/vietati, HTTPS, file ID e descrizione del tool.

## Applicazione

Estrai questo ZIP nella **radice del repository** mantenendo i percorsi e sovrascrivendo i tre file Java esistenti. Il file test `OpenAiFileDownloadServiceTest.java` è nuovo. Non sovrascrivere o modificare `pom.xml`, `application.yml`, token e variabili Render.

```bash
./mvnw clean test
./mvnw package -DskipTests
```

Se i test passano, commit/push sul branch `main` per il deploy Render.

## Prossimo test

Apri una nuova chat con il plugin aggiornato, allega un MP4 di test e prepara una **nuova bozza**. Prima di qualsiasi nuovo tentativo di upload, verifica lo stato della bozza precedente e YouTube, perché un upload fallito dopo l'avvio può avere esito incerto. Se ricevi `DOWNLOAD_FAILED`, controlla in Render la stringa `MCP_FILE_DOWNLOAD_FAILED`. Se è `HOST_NOT_ALLOWED`, considera solo l'hostname effettivo, mai l'URL con firma o token, e aggiorna eventualmente `CHATGPT_FILE_ALLOWED_HOSTS` con un host HTTPS OpenAI ufficiale verificato. Se è `REDIRECT_BLOCKED`, non sbloccare redirect indiscriminati.

Il testo del tool scoraggia YouTube Studio ma **non può disabilitare tecnicamente gli altri strumenti della chat**. Non è stato effettuato un upload YouTube reale e questa patch non è stata compilata automaticamente nel presente ambiente; i test vanno eseguiti nel repository.
