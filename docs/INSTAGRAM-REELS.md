# Reel Instagram con Chewbecca Studio

Allega uno o più MP4 e scrivi:

> Programma questi Reel solo su Instagram. Crea un titolo e una descrizione dal contenuto di ciascun video. Scegli un fotogramma adatto alla copertina e usa lo stile Chewbecca Studio. Usa i migliori giorni e orari delle prossime tre settimane. Mostrami copertine, orari e stato.

`chewbecca_schedule_instagram_reel` registra un Reel per chiamata quando l'utente chiede esplicitamente di programmarlo. ChatGPT prepara i testi e indica il secondo della copertina. Il backend crea la copertina JPEG verticale 1080 × 1920 dal fotogramma, con titolazione bianca e gialla. Su Instagram il titolo appare nella prima riga della didascalia. Il Reel compare anche nel feed Instagram. Il flusso usa esclusivamente l'API Instagram di Meta.

`chewbecca_instagram_reels_status` mostra la coda e gli esiti. `SCHEDULED` indica che il backend ha registrato il lavoro, `PUBLISHED` che Meta ha confermato la pubblicazione. Una risposta incerta viene verificata tramite lo stato del container. L'esito `UNKNOWN` richiede verifica su Instagram e la stessa richiesta non ripubblica il contenuto.

## Come vengono scelti gli orari

Le date sono in Europe/Rome. La finestra comprende oggi e i successivi 20 giorni di calendario. Il sistema sceglie al massimo un Reel al giorno nel calendario Chewbecca Studio. Usa fino a 50 contenuti recenti da Meta, filtra i Reel dai 7 ai 90 giorni di età e normalizza `like + 3 × commenti` rispetto alla mediana della relativa fascia d'età. Combina gli scostamenti per giorno della settimana e per fascia di un'ora e riporta la dimensione del campione. Questa metrica misura interazioni osservate e risente anche di tema, distribuzione e promozioni. Se il campione è insufficiente, propone una fascia esplorativa dichiarata, ispirata alla [ricerca aggregata di Buffer](https://buffer.com/resources/when-is-the-best-time-to-post-on-instagram/). Il calendario comprende le programmazioni effettuate tramite Chewbecca Studio.

## Render e Meta

- `APP_INSTAGRAM_ENABLED=true` abilita l'integrazione esistente.
- `APP_INSTAGRAM_REELS_ENABLED=true` è il default quando Instagram è attivo. Imposta `false` per rimuovere strumenti e worker.
- `APP_INSTAGRAM_REELS_LIVE_ENABLED=true` abilita la coda reale e il worker. Imposta `false` per sospenderli.
- `APP_INSTAGRAM_REELS_STORAGE=/var/data/instagram-reels` usa il disco persistente Render. Configura la directory su un volume persistente anche in altri ambienti. Il limite è 100 MiB per MP4 e 280 MiB complessivi per i file della coda.
- `APP_INSTAGRAM_PUBLIC_URL` o `MCP_PUBLIC_URL` deve indicare l'origine HTTPS raggiungibile da Meta. Il video e la copertina sono serviti tramite URL casuali durante la lavorazione. I file vengono rimossi a fine lavorazione. Il container è creato vicino all'orario, perché scade dopo 24 ore.
- Il collegamento Meta salvato deve concedere `instagram_content_publish` al profilo Instagram collegato. Verifica `publishPermission: true` con `chewbecca_instagram_connection_status`. I permessi di lettura del collegamento restano necessari per raccogliere lo storico.
- La migrazione Flyway `V13` aggiunge una tabella dedicata. L'immagine Docker include FFmpeg per preparare il video e la copertina.

Riferimenti Meta [Content Publishing](https://developers.facebook.com/documentation/instagram-platform/content-publishing), [IG User Media](https://developers.facebook.com/documentation/instagram-platform/instagram-graph-api/reference/ig-user/media).
