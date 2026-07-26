# Fix risposta Meta `text/javascript`

Meta può restituire payload JSON con l'header HTTP:

```text
Content-Type: text/javascript;charset=UTF-8
```

Spring `RestClient`, quando viene richiesto direttamente `JsonNode.class`, non trova un
`HttpMessageConverter` JSON compatibile con quel media type e solleva
`UnknownContentTypeException`.

La correzione applicata legge le risposte Meta come `byte[]` e le deserializza
esplicitamente con Jackson. Il fix copre:

- scambio dell'authorization code;
- debug del token;
- tutte le chiamate successive alla Graph API per Facebook e Instagram.

Non sono richieste modifiche alle variabili d'ambiente o alla configurazione OAuth Meta.
