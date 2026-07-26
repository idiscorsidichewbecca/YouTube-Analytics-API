# Meta OAuth user-token fix

Correzioni applicate al callback Facebook Login for Business:

- supporto ai `granular_scopes.target_ids` restituiti da `/debug_token`;
- recupero diretto delle Pagine autorizzate quando `/me/accounts` è vuoto;
- supporto sia a `instagram_business_account` sia a `connected_instagram_account`;
- validazione che il token appartenga al `META_APP_ID` configurato;
- messaggio diagnostico leggibile nella pagina callback senza esporre token;
- log per fasi OAuth senza registrare authorization code, access token o app secret.
