package it.chewbecca.youtubeanalytics.oauth;

import it.chewbecca.youtubeanalytics.client.YouTubeDataClient;
import it.chewbecca.youtubeanalytics.config.GoogleOAuthProperties;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;

@Service
public class YoutubeOAuthService {

    private static final String AUTHORIZATION_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    public static final String MONETARY_ANALYTICS_SCOPE =
            "https://www.googleapis.com/auth/yt-analytics-monetary.readonly";
    private static final List<String> SCOPES = List.of(
            "https://www.googleapis.com/auth/youtube.readonly",
            "https://www.googleapis.com/auth/yt-analytics.readonly",
            MONETARY_ANALYTICS_SCOPE,
            "https://www.googleapis.com/auth/youtube.upload"
    );

    private final GoogleOAuthProperties properties;
    private final OAuthStateStore stateStore;
    private final GoogleTokenClient tokenClient;
    private final YouTubeDataClient dataClient;
    private final YoutubeCredentialRepository repository;
    private final CredentialCryptoService cryptoService;

    public YoutubeOAuthService(GoogleOAuthProperties properties,
                               OAuthStateStore stateStore,
                               GoogleTokenClient tokenClient,
                               YouTubeDataClient dataClient,
                               YoutubeCredentialRepository repository,
                               CredentialCryptoService cryptoService) {
        this.properties = properties;
        this.stateStore = stateStore;
        this.tokenClient = tokenClient;
        this.dataClient = dataClient;
        this.repository = repository;
        this.cryptoService = cryptoService;
    }

    public URI createAuthorizationUri() {
        String state = stateStore.create();

        return UriComponentsBuilder.fromUriString(AUTHORIZATION_URL)
                .queryParam("client_id", properties.clientId())
                .queryParam("redirect_uri", properties.redirectUri())
                .queryParam("response_type", "code")
                .queryParam("scope", String.join(" ", SCOPES))
                .queryParam("access_type", "offline")
                .queryParam("prompt", "consent")
                .queryParam("login_hint", properties.loginHint())
                .queryParam("include_granted_scopes", true)
                .queryParam("state", state)
                .build()
                .encode()
                .toUri();
    }

    @Transactional
    @CacheEvict(cacheNames = {"channelMetadata", "videoCatalog"}, allEntries = true)
    public YoutubeCredential connect(String code, String state) {
        if (!stateStore.consume(state)) {
            throw new IllegalArgumentException("Parametro OAuth state assente, scaduto o non valido");
        }
        if (!StringUtils.hasText(code)) {
            throw new IllegalArgumentException("Google non ha restituito un authorization code");
        }

        GoogleTokenClient.TokenResponse tokens = tokenClient.exchangeAuthorizationCode(code);
        List<YouTubeDataClient.ChannelMetadata> channels = dataClient.getOwnedChannels(tokens.accessToken());
        YouTubeDataClient.ChannelMetadata selected = selectChannel(channels);

        YoutubeCredential credential = repository.findById(YoutubeCredential.SINGLETON_ID).orElse(null);
        String encryptedRefreshToken = tokens.refreshToken() == null ? null : cryptoService.encrypt(tokens.refreshToken());
        if (credential == null && encryptedRefreshToken == null) {
            throw new IllegalStateException("Google non ha restituito il refresh token. Revoca l'accesso precedente e ripeti il collegamento.");
        }
        if (credential != null
                && encryptedRefreshToken == null
                && !selected.channelId().equals(credential.getChannelId())) {
            throw new IllegalStateException(
                    "Il canale autorizzato è cambiato ma Google non ha restituito un nuovo refresh token. " +
                            "Revoca l'accesso dell'app dal tuo account Google e ripeti il collegamento."
            );
        }

        if (credential == null) {
            credential = YoutubeCredential.create(
                    cryptoService.encrypt(tokens.accessToken()),
                    encryptedRefreshToken,
                    tokens.expiresAt(),
                    tokens.tokenType(),
                    resolveGrantedScopes(tokens.scope()),
                    selected.channelId(),
                    selected.title(),
                    selected.uploadsPlaylistId()
            );
        } else {
            credential.reconnect(
                    cryptoService.encrypt(tokens.accessToken()),
                    encryptedRefreshToken,
                    tokens.expiresAt(),
                    tokens.tokenType(),
                    resolveGrantedScopes(tokens.scope()),
                    selected.channelId(),
                    selected.title(),
                    selected.uploadsPlaylistId()
            );
        }
        return repository.save(credential);
    }

    @Transactional
    @CacheEvict(cacheNames = {"channelMetadata", "videoCatalog"}, allEntries = true)
    public void disconnect() {
        repository.findById(YoutubeCredential.SINGLETON_ID).ifPresent(repository::delete);
    }

    @Transactional(readOnly = true)
    public ConnectionStatus status() {
        return repository.findById(YoutubeCredential.SINGLETON_ID)
                .map(credential -> new ConnectionStatus(
                        true,
                        credential.getChannelId(),
                        credential.getChannelTitle(),
                        credential.getUploadsPlaylistId(),
                        credential.getConnectedAt(),
                        credential.getUpdatedAt(),
                        credential.getAccessTokenExpiresAt(),
                        hasScope(credential.getScopes(), MONETARY_ANALYTICS_SCOPE)
                ))
                .orElseGet(() -> new ConnectionStatus(false, null, null, null, null, null, null, false));
    }

    private String resolveGrantedScopes(String tokenResponseScopes) {
        return StringUtils.hasText(tokenResponseScopes)
                ? tokenResponseScopes
                : String.join(" ", SCOPES);
    }

    private boolean hasScope(String grantedScopes, String requiredScope) {
        if (!StringUtils.hasText(grantedScopes)) {
            return false;
        }
        return List.of(grantedScopes.trim().split("\\s+"))
                .contains(requiredScope);
    }

    private YouTubeDataClient.ChannelMetadata selectChannel(List<YouTubeDataClient.ChannelMetadata> channels) {
        if (channels.isEmpty()) {
            throw new IllegalStateException("L'account Google selezionato non restituisce alcun canale YouTube gestito");
        }

        if (StringUtils.hasText(properties.expectedChannelId())) {
            return channels.stream()
                    .filter(channel -> properties.expectedChannelId().equals(channel.channelId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Il canale configurato " + properties.expectedChannelId() +
                                    " non è presente tra i canali autorizzati: " + describe(channels)
                    ));
        }

        if (channels.size() > 1) {
            throw new IllegalStateException(
                    "L'account gestisce più canali. Imposta YOUTUBE_EXPECTED_CHANNEL_ID scegliendo uno di: " + describe(channels)
            );
        }
        return channels.getFirst();
    }

    private String describe(List<YouTubeDataClient.ChannelMetadata> channels) {
        return channels.stream()
                .map(channel -> channel.title() + " (" + channel.channelId() + ")")
                .reduce((left, right) -> left + ", " + right)
                .orElse("nessuno");
    }

    public record ConnectionStatus(
            boolean connected,
            String channelId,
            String channelTitle,
            String uploadsPlaylistId,
            java.time.Instant connectedAt,
            java.time.Instant updatedAt,
            java.time.Instant accessTokenExpiresAt,
            boolean monetaryAnalyticsGranted
    ) {
    }
}
