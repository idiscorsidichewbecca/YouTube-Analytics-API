package it.chewbecca.youtubeanalytics.oauth;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping
public class YoutubeOAuthController {

    private final YoutubeOAuthService oauthService;

    public YoutubeOAuthController(YoutubeOAuthService oauthService) {
        this.oauthService = oauthService;
    }

    @GetMapping("/admin/youtube/connect")
    public ResponseEntity<Void> connect() {
        return ResponseEntity.status(302)
                .location(oauthService.createAuthorizationUri())
                .build();
    }

    @GetMapping("/oauth2/google/callback")
    public ResponseEntity<String> callback(@RequestParam(required = false) String code,
                                           @RequestParam(required = false) String state,
                                           @RequestParam(required = false) String error) {
        if (error != null) {
            return html(400, "Autorizzazione Google negata", "Errore restituito da Google: " + escape(error));
        }
        YoutubeCredential credential = oauthService.connect(code, state);
        return html(200,
                "Canale collegato",
                "Il canale <strong>" + escape(credential.getChannelTitle()) + "</strong> (" +
                        escape(credential.getChannelId()) + ") è stato collegato correttamente. Puoi chiudere questa pagina.");
    }

    @GetMapping("/admin/youtube/status")
    public YoutubeOAuthService.ConnectionStatus status() {
        return oauthService.status();
    }

    @DeleteMapping("/admin/youtube/disconnect")
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
