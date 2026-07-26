# Meta OAuth diagnostic build

Questa build non modifica gli endpoint esistenti. Aggiunge:

- logging esplicito dello stage OAuth Meta;
- `saveAndFlush` per far emergere immediatamente gli errori PostgreSQL;
- visualizzazione nella pagina callback del tipo di eccezione e della root cause, senza token.

Dopo il deploy, ripartire da `/admin/meta/connect`.
