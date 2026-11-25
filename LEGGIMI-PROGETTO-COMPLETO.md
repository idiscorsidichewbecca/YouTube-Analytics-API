# Chewbecca Studio — progetto completo

Questo archivio contiene tutto il progetto Maven, non una patch. Estrai in una cartella separata e verifica la build con `./mvnw clean verify` (Windows: `mvnw.cmd clean verify`). Poi integra nel repository Git esistente mantenendo la sua cartella `.git`, senza cancellare la cronologia. Rimuovi dal repository eventuali residui `instagram/` (Python), `InstagramBridgeClient.java`, `ChewbeccaMcpInstagramTools.java` e `README-INSTAGRAM.md`.

Le impostazioni Render esistenti di Google e Meta rimangono valide. Vedi README-INSTAGRAM-QUIZ.md per configurare il modulo e verificare il permesso Meta `instagram_content_publish`. Pubblicazione disabilitata per impostazione predefinita. Nessuna pubblicazione o modifica a Render/GitHub effettuata.

Verifiche: 25 smoke check Java 21 superati. Maven wrapper tentato, bloccato dalla risoluzione DNS di repo.maven.apache.org; build integrata non attestata. Esclusi database locali, .git, target, IDE e Python.
