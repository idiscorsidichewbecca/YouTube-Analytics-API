# Bot analytics Telegram senza AI

Il bot Telegram interroga direttamente i servizi YouTube già presenti
nell'applicazione Spring Boot. Non invia prompt o dati a OpenAI, non usa modelli
generativi e non genera costi per token AI.

Il funzionamento è deterministico:

~~~text
topic Telegram
  -> webhook Spring Boot su Render
  -> riconoscimento del comando o della frase
  -> servizi Java YouTube Analytics
  -> calcoli e formattazione Java
  -> risposta nello stesso topic
~~~

Il GPT Chewbecca-Strategist configurato in ChatGPT continua a utilizzare le
Actions esistenti e non viene modificato da questa integrazione.

## Comandi

| Comando | Risultato |
|---|---|
| /ping | Verifica bot e webhook |
| /help o /comandi | Mostra i comandi disponibili |
| /topvideo [inizio fine] | Top 10 video per views |
| /topshort [inizio fine] | Top 10 Short per views |
| /adesso | Ultimi 28 giorni completi contro i 28 precedenti |
| /trimestre | Ultimi 3 mesi completi contro i 3 precedenti |
| /guadagni30 | Ricavi stimati degli ultimi 30 giorni completi |
| /ripartenze | Confronta le finestre successive alle ripartenze configurate |
| /confrontomese YYYY-MM | Ultimo mese completo contro un mese scelto |
| /confrontoanno YYYY | Anno corrente contro lo stesso intervallo dell'anno scelto |
| /ultimovideo | Ultimo long video contro i 10 precedenti alla stessa età |

Nei comandi rivolti al gruppo è consigliato aggiungere lo username:

~~~text
/topvideo@chewbecca_strategist_bot
/confrontomese@chewbecca_strategist_bot 2025-09
~~~

Il bot riconosce anche alcune frasi quando viene menzionato:

~~~text
@chewbecca_strategist_bot dimmi i top video
@chewbecca_strategist_bot dimmi i top Short
@chewbecca_strategist_bot come sta andando il canale?
@chewbecca_strategist_bot confronta gli ultimi 3 mesi con i precedenti
@chewbecca_strategist_bot quanto abbiamo guadagnato negli ultimi 30 giorni?
@chewbecca_strategist_bot come sta andando l'ultimo video rispetto agli altri?
~~~

Non è un interprete linguistico generale: le varianti riconosciute sono regole
esplicite nel codice.

## Endpoint dei comandi

L'elenco utilizzato anche da /help è esposto in JSON:

~~~text
GET /api/integrations/telegram/commands
~~~

La risposta dichiara esplicitamente:

~~~json
{
  "mode": "DETERMINISTIC_NO_AI",
  "usesArtificialIntelligence": false
}
~~~

Seguono descrizione, sintassi ed esempi di tutti i comandi implementati.

## Significato dei confronti

- /adesso: ultimi 28 giorni completi contro i 28 immediatamente precedenti.
- /trimestre: ultimi tre mesi di calendario completi contro i tre precedenti.
- /confrontomese: ultimo mese di calendario completo contro il mese indicato.
- /confrontoanno: dal 1° gennaio all'ultimo giorno completo disponibile,
  confrontato con lo stesso intervallo dell'anno richiesto.
- /ultimovideo: usa l'ultimo contenuto classificato VIDEO, esclude gli Short e
  confronta views, ore, percentuale media vista e iscritti netti con la media
  dei dieci video precedenti. Ogni video è misurato sullo stesso numero di
  giorni completi dalla propria pubblicazione.

Le differenze assolute, percentuali, medie e posizioni sono calcolate in Java.

## Configurare le ripartenze

Per /ripartenze aggiungere su Render:

~~~dotenv
CHANNEL_RESTART_DATES=Nome 1|2024-09-01,Nome 2|2025-02-15
CHANNEL_RESTART_WINDOW_DAYS=30
~~~

Ogni voce contiene etichetta e data separate da |. Il bot confronta i primi
30 giorni dopo ciascuna data, oppure la finestra configurata. Sono necessarie
almeno due ripartenze.

## Variabili Render

~~~dotenv
TELEGRAM_ENABLED=true
TELEGRAM_BOT_TOKEN=...
TELEGRAM_BOT_USERNAME=chewbecca_strategist_bot
TELEGRAM_WEBHOOK_SECRET=...
TELEGRAM_ALLOWED_CHAT_ID=-1001947350438

ASSISTANT_ENABLED=false
~~~

OPENAI_API_KEY non è necessaria. Le configurazioni Google, Neon e
GPT_ACTION_API_KEY restano invariate.

Il webhook è:

~~~text
POST /api/integrations/telegram/webhook
~~~

## Verifica

~~~bash
./mvnw test
~~~

I test coprono sicurezza del webhook, topic Telegram, deduplicazione, catalogo
dei comandi, riconoscimento delle frasi e calcolo degli intervalli.
