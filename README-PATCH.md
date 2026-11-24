# Chewbecca Studio — Fix definitivo per host regionali OpenAI

## Contesto
La versione di partenza corrisponde al commit Render/GitHub `d3fa796`.
Il caricamento MP4 risultava bloccato da `HOST_NOT_ALLOWED` perché ChatGPT ha fornito
host regionali differenti, per esempio `sdmntprdenmarkeast.oaiusercontent.com` e
`sdmntprpolandcentral.oaiusercontent.com`.

## Cosa cambia
- `OpenAiFileDownloadService.java`: oltre agli hostname esatti configurati,
  consente gli host di un solo livello che rispettano il pattern
  `sdmntpr...oaiusercontent.com`, riconoscendo dinamicamente le regioni.
- La regola NON è un wildcard generico `*.oaiusercontent.com`.
- Rimangono obbligatori HTTPS, porta 443 (o default), assenza di userinfo/fragment,
  redirect HTTP bloccati, limite 300 MiB e verifica della firma MP4.
- `OpenAiFileDownloadServiceTest.java`: test aggiuntivi per regioni nuove,
  host imitatori, HTTP, credenziali nell'URL, porta non standard e fragment.
- `MODIFICHE.diff`: diff unificato della patch rispetto ai file correnti.

## Installazione
1. Estrarre questo ZIP nella RADICE del repository
   `idiscorsidichewbecca/YouTube-Analytics-API`, mantenendo i percorsi.
2. Sostituire solo i due `.java` presenti nello ZIP. Non sovrascrivere altri file.
3. Eseguire `./mvnw test`, poi commit e push su `main`.
4. Attendere il deploy Render `Live`, poi riprovare una NUOVA bozza da ChatGPT.

## Configurazione Render
Non è più necessario aggiungere gli hostname regionali uno per uno a
`CHATGPT_FILE_ALLOWED_HOSTS`. Può restare impostata sui domini esatti già
utilizzati (per esempio `files.oaiusercontent.com,files.openai.com`).
NON inserire `*` o domini non verificati nella variabile.

## Limitazioni e sicurezza
Questo fix copre i domini regionali con prefisso `sdmntpr` osservati nei log.
Se OpenAI cambia il proprio schema di hostname, la policy deve essere rivista:
non è sicuro aprire indiscriminatamente tutti gli host HTTPS.
Non elimina eventuali problemi successivi con download scaduti, Google OAuth,
quota YouTube o video upload. Non è stato eseguito un upload reale.
La nuova logica NON apre YouTube Studio e non richiede nuovi login Google.
I test JUnit vanno eseguiti nel repository prima della pubblicazione.
