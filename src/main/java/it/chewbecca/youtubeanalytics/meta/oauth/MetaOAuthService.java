package it.chewbecca.youtubeanalytics.meta.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import it.chewbecca.youtubeanalytics.meta.client.MetaGraphClient;
import it.chewbecca.youtubeanalytics.meta.config.MetaProperties;
import it.chewbecca.youtubeanalytics.oauth.CredentialCryptoService;
import it.chewbecca.youtubeanalytics.oauth.OAuthStateStore;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class MetaOAuthService {

    private final MetaProperties properties;
    private final OAuthStateStore stateStore;
    private final MetaTokenClient tokenClient;
    private final MetaGraphClient graphClient;
    private final MetaCredentialRepository repository;
    private final CredentialCryptoService cryptoService;

    public MetaOAuthService(MetaProperties properties,
                            OAuthStateStore stateStore,
                            MetaTokenClient tokenClient,
                            MetaGraphClient graphClient,
                            MetaCredentialRepository repository,
                            CredentialCryptoService cryptoService) {
        this.properties = properties;
        this.stateStore = stateStore;
        this.tokenClient = tokenClient;
        this.graphClient = graphClient;
        this.repository = repository;
        this.cryptoService = cryptoService;
    }

    public URI createAuthorizationUri() {
        properties.requireConfigured();
        String state = stateStore.create();
        return UriComponentsBuilder
                .fromUriString("https://www.facebook.com/" + properties.normalizedGraphApiVersion() + "/dialog/oauth")
                .queryParam("client_id", properties.appId())
                .queryParam("redirect_uri", properties.redirectUri())
                .queryParam("state", state)
                .queryParam("config_id", properties.configurationId())
                .queryParam("response_type", "code")
                .queryParam("override_default_response_type", true)
                .build()
                .encode()
                .toUri();
    }

    @Transactional
    @CacheEvict(cacheNames = {"metaFacebookPage", "metaInstagramAccount", "metaFacebookContent", "metaInstagramMedia"}, allEntries = true)
    public MetaCredential connect(String code, String state) {
        properties.requireConfigured();
        if (!stateStore.consume(state)) {
            throw new IllegalArgumentException("Parametro OAuth state Meta assente, scaduto o non valido");
        }
        if (!StringUtils.hasText(code)) {
            throw new IllegalArgumentException("Meta non ha restituito un authorization code");
        }

        MetaTokenClient.TokenResponse tokens = tokenClient.exchangeAuthorizationCode(code);
        MetaTokenClient.DebugToken debug = tokenClient.debug(tokens.accessToken());
        JsonNode business = graphClient.get(
                "/me",
                tokens.accessToken(),
                Map.of("fields", "id,name"),
                "lettura business collegato"
        );
        List<JsonNode> pages = graphClient.getAll(
                "/me/accounts",
                tokens.accessToken(),
                Map.of("fields", "id,name,access_token,tasks,instagram_business_account{id,username,name}"),
                100,
                "elenco Pagine autorizzate"
        );
        SelectedAssets selected = selectAssets(pages);
        Instant expiresAt = debug.expiresAt() != null ? debug.expiresAt() : tokens.fallbackExpiresAt(properties);
        String scopes = debug.scopes() == null ? null : String.join(" ", debug.scopes());
        String pageToken = StringUtils.hasText(selected.pageAccessToken())
                ? selected.pageAccessToken()
                : tokens.accessToken();

        MetaCredential credential = repository.findById(MetaCredential.SINGLETON_ID).orElse(null);
        if (credential == null) {
            credential = MetaCredential.create(
                    cryptoService.encrypt(tokens.accessToken()),
                    cryptoService.encrypt(pageToken),
                    expiresAt,
                    tokens.tokenType(),
                    scopes,
                    textOrNull(business.path("id")),
                    textOrNull(business.path("name")),
                    selected.pageId(),
                    selected.pageName(),
                    selected.instagramAccountId(),
                    selected.instagramUsername()
            );
        } else {
            credential.reconnect(
                    cryptoService.encrypt(tokens.accessToken()),
                    cryptoService.encrypt(pageToken),
                    expiresAt,
                    tokens.tokenType(),
                    scopes,
                    textOrNull(business.path("id")),
                    textOrNull(business.path("name")),
                    selected.pageId(),
                    selected.pageName(),
                    selected.instagramAccountId(),
                    selected.instagramUsername()
            );
        }
        return repository.save(credential);
    }

    @Transactional
    @CacheEvict(cacheNames = {"metaFacebookPage", "metaInstagramAccount", "metaFacebookContent", "metaInstagramMedia"}, allEntries = true)
    public void disconnect() {
        repository.findById(MetaCredential.SINGLETON_ID).ifPresent(repository::delete);
    }

    @Transactional(readOnly = true)
    public ConnectionStatus status() {
        boolean configured = properties.enabled()
                && StringUtils.hasText(properties.appId())
                && StringUtils.hasText(properties.appSecret())
                && StringUtils.hasText(properties.configurationId())
                && properties.redirectUri() != null;
        return repository.findById(MetaCredential.SINGLETON_ID)
                .map(credential -> new ConnectionStatus(
                        properties.enabled(),
                        configured,
                        true,
                        credential.getBusinessId(),
                        credential.getBusinessName(),
                        credential.getPageId(),
                        credential.getPageName(),
                        credential.getInstagramAccountId(),
                        credential.getInstagramUsername(),
                        credential.getConnectedAt(),
                        credential.getUpdatedAt(),
                        credential.getAccessTokenExpiresAt(),
                        credential.getAccessTokenExpiresAt() != null && credential.getAccessTokenExpiresAt().isAfter(Instant.now())
                ))
                .orElseGet(() -> new ConnectionStatus(
                        properties.enabled(), configured, false,
                        null, null, null, null, null, null,
                        null, null, null, false
                ));
    }

    private SelectedAssets selectAssets(List<JsonNode> pages) {
        List<SelectedAssets> candidates = new ArrayList<>();
        for (JsonNode page : pages) {
            JsonNode instagram = page.path("instagram_business_account");
            String pageId = textOrNull(page.path("id"));
            String instagramId = textOrNull(instagram.path("id"));
            if (pageId == null || instagramId == null) {
                continue;
            }
            candidates.add(new SelectedAssets(
                    pageId,
                    page.path("name").asText("Pagina Facebook"),
                    textOrNull(page.path("access_token")),
                    instagramId,
                    instagram.path("username").asText(instagram.path("name").asText("account-instagram"))
            ));
        }
        if (candidates.isEmpty()) {
            throw new IllegalStateException(
                    "Meta non ha restituito alcuna Pagina autorizzata con un account Instagram professionale collegato"
            );
        }

        if (StringUtils.hasText(properties.expectedPageId())) {
            return candidates.stream()
                    .filter(candidate -> properties.expectedPageId().equals(candidate.pageId()))
                    .filter(candidate -> !StringUtils.hasText(properties.expectedInstagramAccountId())
                            || properties.expectedInstagramAccountId().equals(candidate.instagramAccountId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "La Pagina Meta configurata non è tra le risorse autorizzate: " + describe(candidates)
                    ));
        }
        if (StringUtils.hasText(properties.expectedInstagramAccountId())) {
            return candidates.stream()
                    .filter(candidate -> properties.expectedInstagramAccountId().equals(candidate.instagramAccountId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "L'account Instagram configurato non è tra le risorse autorizzate: " + describe(candidates)
                    ));
        }
        if (candidates.size() > 1) {
            throw new IllegalStateException(
                    "Sono state autorizzate più coppie Pagina/Instagram. Imposta META_EXPECTED_PAGE_ID oppure "
                            + "META_EXPECTED_INSTAGRAM_ACCOUNT_ID scegliendo una di: " + describe(candidates)
            );
        }
        return candidates.getFirst();
    }

    private String describe(List<SelectedAssets> candidates) {
        return candidates.stream()
                .map(candidate -> candidate.pageName() + " (page=" + candidate.pageId()
                        + ", instagram=@" + candidate.instagramUsername() + ", ig=" + candidate.instagramAccountId() + ")")
                .reduce((left, right) -> left + ", " + right)
                .orElse("nessuna");
    }

    private String textOrNull(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() || !StringUtils.hasText(node.asText())
                ? null
                : node.asText();
    }

    private record SelectedAssets(
            String pageId,
            String pageName,
            String pageAccessToken,
            String instagramAccountId,
            String instagramUsername
    ) {
    }

    public record ConnectionStatus(
            boolean enabled,
            boolean configured,
            boolean connected,
            String businessId,
            String businessName,
            String facebookPageId,
            String facebookPageName,
            String instagramAccountId,
            String instagramUsername,
            Instant connectedAt,
            Instant updatedAt,
            Instant accessTokenExpiresAt,
            boolean tokenValidByStoredExpiry
    ) {
    }
}
