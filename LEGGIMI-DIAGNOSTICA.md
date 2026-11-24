# Diagnosi HTTP 500 MCP — 24 novembre 2026

Base: `YouTube-Analytics-API(5).zip`, commit `1d0a3133` (lo stesso distribuito su Render).

Nei log Render: `NoResourceFoundException` per `/.well-known/oauth-protected-resource`
e `/.well-known/oauth-authorization-server`. `ApiExceptionHandler` rimappa 404 mancanti a HTTP 500.

## Patch minima
- `ApiExceptionHandler.java`: ritorna 404 quando l'endpoint richiesto non esiste.
- `McpStartupDiagnostics.java`: all'avvio registra `MCP_DIAGNOSTICS` con i due flag
  booleani risolti e la presenza dei bean di OAuth e metadata controller.
  Non logga credenziali, token o valori di secret.

## Controlli
1. Compilare e testare in pipeline Java 21. La compilazione end-to-end non e' stata eseguita qui.
2. Nei log d'avvio su Render cercare `MCP_DIAGNOSTICS`.
3. Valori attesi: `app.mcp.enabled=true`, `spring.ai.mcp.server.enabled=true`,
   `metadataController=true`, `oauthConfig=true`.
4. Se un valore diverge, intervenire su configurazione/registrazione dei bean, senza toccare i segreti.
5. Se tutti sono veri, verificare mapping Spring MVC, security chain e configurazione MCP.

Questo fix 404 non registra automaticamente l'endpoint se MCP rimane disabilitato.
Non abilitare le operazioni di pubblicazione fino ai test end-to-end OAuth2/PKCE.
