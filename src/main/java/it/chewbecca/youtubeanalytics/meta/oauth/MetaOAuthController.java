package it.chewbecca.youtubeanalytics.meta.oauth;

import it.chewbecca.youtubeanalytics.meta.client.MetaGraphApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping
public class MetaOAuthController {

    private static final Logger log = LoggerFactory.getLogger(MetaOAuthController.class);

    private final MetaOAuthService oauthService;

    public MetaOAuthController(MetaOAuthService oauthService) {
        this.oauthService = oauthService;
    }

    @GetMapping("/admin/meta/connect")
    public ResponseEntity<Void> connect() {
        return ResponseEntity.status(302)
                .location(oauthService.createAuthorizationUri())
                .build();
    }

    @GetMapping("/oauth2/meta/callback")
    public ResponseEntity<String> callback(@RequestParam(required = false) String code,
                                           @RequestParam(required = false) String state,
                                           @RequestParam(required = false) String error,
                                           @RequestParam(name = "error_description", required = false) String errorDescription) {
        if (error != null) {
            String detail = errorDescription == null ? error : error + ": " + errorDescription;
            return html(400, "Autorizzazione Meta negata", "Errore restituito da Meta: " + escape(detail));
        }
        try {
            MetaCredential credential = oauthService.connect(code, state);
            return html(
                    200,
                    "Facebook e Instagram collegati",
                    "La Pagina <strong>" + escape(credential.getPageName()) + "</strong> e l'account Instagram "
                            + "<strong>@" + escape(credential.getInstagramUsername()) + "</strong> sono stati collegati. "
                            + "Puoi chiudere questa pagina."
            );
        } catch (MetaGraphApiException ex) {
            log.error("Callback Meta fallita operation={} status={} code={} subcode={} body={}",
                    ex.getOperation(), ex.getStatusCode(), ex.getGraphCode(), ex.getGraphSubcode(), ex.getResponseBody());
            return html(502, "Collegamento Meta non riuscito", escape(ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            log.warn("Callback Meta non valida: {}", ex.getMessage());
            return html(400, "Collegamento Meta non valido", escape(ex.getMessage()));
        } catch (IllegalStateException ex) {
            log.error("Callback Meta fallita", ex);
            return html(500, "Collegamento Meta non riuscito", escape(ex.getMessage()));
        } catch (Exception ex) {
            Throwable root = rootCause(ex);
            String detail = ex.getClass().getSimpleName()
                    + " / " + root.getClass().getSimpleName()
                    + ": " + safeMessage(root);
            log.error("Callback Meta fallita con eccezione non gestita: {}", detail, ex);
            return html(500, "Collegamento Meta non riuscito", escape(detail));
        }
    }

    @GetMapping("/admin/meta/status")
    public MetaOAuthService.ConnectionStatus status() {
        return oauthService.status();
    }

    @DeleteMapping("/admin/meta/disconnect")
    public ResponseEntity<Void> disconnect() {
        oauthService.disconnect();
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<String> html(int status, String title, String message) {
        String body = """
                <!doctype html>
                <html lang="it">
                <head><meta charset="utf-8"><title>%s</title></head>
                <body style="font-family:system-ui;max-width:760px;margin:60px auto;padding:0 20px">
                  <h1>%s</h1>
                  <p>%s</p>
                </body>
                </html>
                """.formatted(escape(title), escape(title), message);
        return ResponseEntity.status(status)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.TEXT_HTML)
                .body(body);
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable == null ? null : throwable.getMessage();
        if (message == null || message.isBlank()) {
            return "nessun messaggio";
        }
        String singleLine = message.replace('\n', ' ').replace('\r', ' ');
        return singleLine.length() <= 1200 ? singleLine : singleLine.substring(0, 1200) + "...";
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
