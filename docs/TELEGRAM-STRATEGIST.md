# Chewbecca-Strategist su Telegram

Questa integrazione porta nel gruppo Telegram il comportamento analitico di
Chewbecca-Strategist. Non invoca direttamente il GPT creato nel Builder di
ChatGPT: usa un modello OpenAI via Responses API, le stesse istruzioni
strategiche e le 44 Actions dichiarate in `openapi-gpt.yaml`.

Il modello può scegliere ed eseguire più Actions nella stessa richiesta,
incrociare i risultati e produrre una risposta finale. Per esempio, può
confrontare views e ricavi, video e Short, intervalli diversi o dati YouTube e
Meta, rispettando i limiti di confrontabilità indicati nelle istruzioni.

## Architettura

```text
topic Telegram
  -> webhook Spring Boot su Render
  -> OpenAI Responses API
  -> una o più Actions /api/v1/**
  -> Google YouTube / Meta
  -> analisi finale nello stesso topic

Neon conserva l'ID della conversazione OpenAI per ogni coppia gruppo + topic.
```

Le API analytics restano protette da `GPT_ACTION_API_KEY`. Quando il modello
richiede una Action, l'applicazione la richiama internamente su localhost e
allega automaticamente il Bearer token: la chiave non viene mai mostrata a
OpenAI o Telegram.

## 1. Creare e aggiungere il bot

In BotFather crea il bot e annota:

- il token;
- lo username reale, che deve terminare con `bot`, per esempio
  `chewbecca_strategist_bot`.

Il nome visualizzato può essere `Chewbecca-Strategist`, ma non sostituisce lo
username. Aggiungi il bot al supergruppo con i topic.

La privacy mode può restare attiva. Il modo più affidabile per iniziare una
domanda è il comando esplicitamente indirizzato al bot:

```text
/ask@chewbecca_strategist_bot Incrocia views e ricavi degli ultimi 28 giorni
```

Dopo la prima risposta, il team può anche rispondere direttamente ai messaggi
del bot. È supportata anche la menzione
`@chewbecca_strategist_bot domanda`, se Telegram la consegna con le impostazioni
del gruppo.

## 2. Recuperare il chat ID

Questa operazione va eseguita prima di configurare il webhook:

1. aggiungere il bot al gruppo;
2. inviare `/ping@chewbecca_strategist_bot` in un topic;
3. dalla propria macchina chiamare `getUpdates` e leggere `message.chat.id`.

```bash
export TELEGRAM_BOT_TOKEN='...'
curl --silent "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/getUpdates"
```

Il chat ID di un supergruppo è negativo e normalmente inizia con `-100`.
Conservarlo integralmente. Non condividere mai il bot token.

## 3. Variabili su Render

Mantenere tutte le variabili già presenti per Google, Meta, Neon e sicurezza,
in particolare `GPT_ACTION_API_KEY`. Aggiungere:

```dotenv
TELEGRAM_ENABLED=true
TELEGRAM_BOT_TOKEN=...
TELEGRAM_BOT_USERNAME=chewbecca_strategist_bot
TELEGRAM_WEBHOOK_SECRET=...
TELEGRAM_ALLOWED_CHAT_ID=-100...

ASSISTANT_ENABLED=true
OPENAI_API_KEY=...
OPENAI_MODEL=gpt-5.6-luna
OPENAI_MAX_OUTPUT_TOKENS=3000
OPENAI_MAX_TOOL_ROUNDS=6
```

Generare il secret Telegram, per esempio con:

```bash
openssl rand -hex 32
```

`TELEGRAM_WEBHOOK_SECRET` deve contenere 16-256 caratteri fra lettere, numeri,
`_` e `-`.

Lasciare vuota `ASSISTANT_ACTIONS_BASE_URL`: su Render il bot richiamerà le
Actions della stessa applicazione su localhost. Impostarla solo se le Actions
vengono spostate su un servizio differente.

L'uso del modello tramite API è fatturato separatamente dall'abbonamento
ChatGPT. Il costo dipende dai token delle domande, delle risposte e dei dati
restituiti dalle Actions.

## 4. Database Neon

Al deploy Flyway esegue automaticamente:

```text
V3__create_telegram_conversations.sql
```

La tabella `telegram_conversations` conserva soltanto il riferimento alla
conversazione OpenAI per ogni `(telegram_chat_id, telegram_thread_id)`. Non è
necessario eseguire SQL manuale. Ogni topic ha quindi una memoria distinta.

## 5. Registrare il webhook

Dopo che il nuovo deploy è healthy:

```bash
export TELEGRAM_BOT_TOKEN='...'
export TELEGRAM_WEBHOOK_SECRET='...'
./scripts/configure-telegram-webhook.sh https://youtube-analytics-api.onrender.com
```

Usare il dominio Render effettivo. Il dominio standard termina con
`.onrender.com`, non `.onrrender.com`.

Lo script registra:

```text
POST /api/integrations/telegram/webhook
```

Telegram invia nell'header `X-Telegram-Bot-Api-Secret-Token` il secret
configurato. Richieste senza il valore corretto vengono respinte.

## 6. Test nel gruppo

Eseguire nello stesso topic:

```text
/ping@chewbecca_strategist_bot
/ask@chewbecca_strategist_bot Quali Short stanno rendendo meglio e perché?
/ask@chewbecca_strategist_bot Ora confrontali con il periodo precedente
/reset@chewbecca_strategist_bot
```

Risultati attesi:

- `/ping` risponde `Bot attivo`;
- `/ask` può attivare una o più Actions e risponde nello stesso topic;
- la seconda domanda usa il contesto della prima;
- `/reset` cancella soltanto la memoria del topic corrente.

Le risposte oltre il limite Telegram vengono divise in più messaggi. Gli update
duplicati vengono ignorati per un'ora. I messaggi provenienti da gruppi diversi
da `TELEGRAM_ALLOWED_CHAT_ID` non vengono elaborati.

## 7. Verifica locale

```bash
./mvnw test
```

I test coprono autenticazione del webhook, routing per topic, comandi,
deduplicazione, trasformazione delle 44 Actions e il ciclo
modello -> Action -> analisi finale.
