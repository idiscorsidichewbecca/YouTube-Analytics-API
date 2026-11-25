package it.chewbecca.youtubeanalytics.api;

import it.chewbecca.youtubeanalytics.oauth.YoutubeOAuthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/integration/youtube")
public class YoutubeIntegrationController {
    private final YoutubeOAuthService oauthService;

    public YoutubeIntegrationController(YoutubeOAuthService oauthService) {
        this.oauthService = oauthService;
    }

    @GetMapping("/status")
    public YoutubeOAuthService.ConnectionStatus status() {
        return oauthService.status();
    }
}
