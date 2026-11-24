# Correzione preliminare: chiave OAuth MCP persistente

**Non attivare MCP prima dei test di integrazione OAuth in staging.**

## Perché
La versione iniziale generava una chiave RSA diversa ad ogni avvio del container. La patch richiede una chiave PKCS#8 RSA 3072 persistente, memorizzata esclusivamente in Render Environment, quindi il `kid` e la firma rimangono costanti attraverso i deploy.

## Configurazione
1. Mantenere `MCP_ENABLED=false` finché non si completano i test.
2. Aprire localmente nel browser `tools/crea-chiave-oauth.html`, generare una sola chiave e copiarla **solo** nel secret Render `MCP_SIGNING_PRIVATE_KEY_PKCS8_B64`.
3. Conservare lo stesso valore per i deploy successivi, predisponendo backup sicuro/rotazione.
4. Mantenere `MCP_PUBLIC_URL` come URL HTTPS canonico del servizio, senza slash finale.
5. Controllare il **redirect URI effettivo** visualizzato nella schermata Plugin di ChatGPT. Il default `https://chatgpt.com/connector_platform_oauth_redirect` potrebbe non coincidere con quello della connessione: in tal caso impostare `MCP_REDIRECT_URI` esattamente come indicato dall'host, non tentare redirect a tentativi.

## Test ancora necessari prima di MCP_ENABLED=true
- Maven compile e tests con Java 21 (questa consegna non ha un build end-to-end eseguito).
- Discovery `/.well-known/oauth-authorization-server`, `/.well-known/oauth-protected-resource/mcp` e disponibilità dei metadati PKCE S256.
- Login amministratore, authorization code + PKCE, scambio token con resource esatta, verifica audience, issuer, scope ed expiry su `/mcp`.
- `tools/list` mostra **solo** `chewbecca_recommend_slot`, `tools/call` con token read-only e 401 senza token.
- Esecuzione su una singola istanza con archiviazione persistente OAuth autorizzazioni: InMemory* è soltanto un prototipo; Render restart potrebbe invalidare sessioni o permessi.
- Review del controllo `resource` attuale: i client possono essere rifiutati, e RFC 8707 deve essere validato end-to-end senza rompere richieste OAuth valide.
- Test con ChatGPT Plus, in particolare la versione del callback URI e la selezione del client pre-registrato.

Non aprire in produzione la pubblicazione Shorts finché questi test e la separazione scope read/write non siano completati.
