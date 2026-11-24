# Stato consegna: 24 novembre 2026

## Implementato nel codice

- Router `/internal/chatgpt/shorts/**` con filtro a credenziale server-to-server separata e attivazione esplicita tramite variabile d'ambiente (default disabilitato).
- Analisi preesistente: storico degli ultimi due mesi e ricerca slot nelle prossime 48 ore.
- Crea bozza con testi generati da ChatGPT, senza dover pagare una seconda chiamata OpenAI API dal backend.
- Ricezione di un riferimento file ChatGPT (`download_url`, `file_id`, `mime_type?`, `file_name?`), URL HTTPS con allowlist di host esatti, no redirect e tetto 300 MB; firma ISO-BMFF `ftyp` controllata prima dell'upload.
- Programmazione con conferma della bozza `CONFERMO <draftId>` e stato recuperabile dopo la richiesta.
- Riferimento TypeScript per i tool MCP ospitati in ChatGPT Sites, incluse dichiarazioni `openai/fileParams`.
- Test sorgente: validazione URL file, casi di conferma mancante, filtro credenziali.

## Non eseguito / non verificato

- Compilazione Maven e JUnit: ambiente di esecuzione senza Maven e dipendenze locali. Eseguito solo controllo sintattico Java (javac senza classpath, che produce i prevedibili errori di import mancanti) e controllo sintattico del riferimento TypeScript (Node 22 con strip-types).
- Nessun deploy su Render, nessuna installazione del plugin in ChatGPT Sites, nessun test OAuth Google, nessun upload YouTube reale.
- Nessuna transcodifica video né trascrizione automatica MP4 lato server. La qualità di titolo e descrizione dipende dalla lettura/trascrizione dell'allegato da parte della chat.
- La verifica delle autorizzazioni Site owner-only avviene all'interno del Site stesso: il backend verifica la chiave separata, non identità multi-utente; condivisione del plugin NON supportata in questa versione.

## Passi di attivazione

1. Eseguire `./mvnw clean verify` in un ambiente con accesso a Maven Central; risolvere eventuali incompatibilità emerse.
2. Configurare e fare deploy del backend seguendo `CHATGPT-SITES-CHEWBECCA.md`.
3. In un ChatGPT Site privato creare gli strumenti MCP a partire da `SITES-PLUGIN-PROMPT.txt` e dal template `sites-mcp-tools.ts`, impostando i segreti server-side.
4. Installare il plugin personale e testare sola raccomandazione.
5. Provare una bozza non pubblicante e l'anteprima dei campi; poi un MP4 di test, con conferma esplicita, verificando YouTube Studio.

**Da non fare:** attivare `CHATGPT_BRIDGE_ENABLED=true` prima di creare e proteggere la chiave, rendere pubblico il sito con i tool di pubblicazione, incollare credenziali in chat, ritentare un upload con stato incerto.
