package it.chewbecca.youtubeanalytics.meta.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import it.chewbecca.youtubeanalytics.meta.client.MetaGraphApiException;
import it.chewbecca.youtubeanalytics.meta.client.MetaGraphClient;
import it.chewbecca.youtubeanalytics.meta.config.MetaProperties;
import it.chewbecca.youtubeanalytics.oauth.CredentialCryptoService;
import it.chewbecca.youtubeanalytics.oauth.OAuthStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Service
public class MetaOAuthService {

    private static final Logger log = LoggerFactory.getLogger(MetaOAuthService.class);

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
        String stage = "validazione configurazione e state";
        try {
            properties.requireConfigured();
        if (!stateStore.consume(state)) {
            throw new IllegalArgumentException("Parametro OAuth state Meta assente, scaduto o non valido");
        }
        if (!StringUtils.hasText(code)) {
            throw new IllegalArgumentException("Meta non ha restituito un authorization code");
        }

        stage = "scambio authorization code";
        log.warn("Meta OAuth stage={}", stage);
        MetaTokenClient.TokenResponse tokens = tokenClient.exchangeAuthorizationCode(code);

        stage = "verifica token ricevuto";
        log.warn("Meta OAuth stage={}", stage);
        MetaTokenClient.DebugToken debug = tokenClient.debug(tokens.accessToken());
        if (StringUtils.hasText(debug.appId()) && !properties.appId().equals(debug.appId())) {
            throw new IllegalStateException("Il token Meta appartiene a un'app diversa da META_APP_ID");
        }

        stage = "lettura identità collegata";
        log.warn("Meta OAuth stage={}, token type={}", stage, debug.type());
        JsonNode business = graphClient.get(
                "/me",
                tokens.accessToken(),
                Map.of("fields", "id,name"),
                "lettura business collegato"
        );
        stage = "recupero Pagine autorizzate";
        List<JsonNode> pages = loadAuthorizedPages(tokens.accessToken(), debug);
        log.warn("Meta OAuth stage={} completato, pages={}", stage, pages.size());
        stage = "selezione Pagina e account Instagram";
        SelectedAssets selected = selectAssets(pages, debug);
        log.warn("Meta OAuth stage={} completato, pageId={}, instagramAccountId={}", stage,
                selected.pageId(), selected.instagramAccountId());
        Instant expiresAt = debug.expiresAt() != null ? debug.expiresAt() : tokens.fallbackExpiresAt(properties);
        String scopes = debug.scopes() == null ? null : String.join(" ", debug.scopes());
        String pageToken = StringUtils.hasText(selected.pageAccessToken())
                ? selected.pageAccessToken()
                : tokens.accessToken();

        stage = "lettura credenziale Meta dal database";
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
        stage = "salvataggio credenziale Meta nel database";
        MetaCredential saved = repository.saveAndFlush(credential);
        log.warn("Meta OAuth stage={} completato", stage);
        return saved;
        } catch (RuntimeException ex) {
            Throwable root = rootCause(ex);
            log.error("Meta OAuth fallita stage={} exception={} rootException={} rootMessage={}",
                    stage,
                    ex.getClass().getName(),
                    root.getClass().getName(),
                    safeMessage(root),
                    ex);
            throw ex;
        }
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
        if (!StringUtils.hasText(message)) {
            return "nessun messaggio";
        }
        String singleLine = message.replace('\n', ' ').replace('\r', ' ');
        return singleLine.length() <= 1200 ? singleLine : singleLine.substring(0, 1200) + "...";
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


    private List<JsonNode> loadAuthorizedPages(String accessToken, MetaTokenClient.DebugToken debug) {
        List<JsonNode> pages = new ArrayList<>();

        try {
            pages = graphClient.getAll(
                    "/me/accounts",
                    accessToken,
                    Map.of("fields", pageFields()),
                    100,
                    "elenco Pagine autorizzate"
            );
            if (!pages.isEmpty()) {
                return pages;
            }
            log.info("Meta OAuth: /me/accounts ha restituito zero Pagine");
        } catch (MetaGraphApiException ex) {
            log.info("Meta OAuth: /me/accounts non disponibile: {}", ex.getMessage());
        }

        // Facebook Login for Business espone gli asset selezionati anche nei granular_scopes
        // del debug token. In alcune configurazioni /me/accounts può risultare vuoto benché
        // l'utente abbia scelto esplicitamente una Pagina durante l'onboarding.
        Set<String> pageTargetIds = new LinkedHashSet<>();
        pageTargetIds.addAll(debug.targetIds("pages_show_list"));
        pageTargetIds.addAll(debug.targetIds("pages_read_engagement"));
        pageTargetIds.addAll(debug.targetIds("read_insights"));

        if (!pageTargetIds.isEmpty()) {
            log.info("Meta OAuth: provo il recupero diretto di {} Page target IDs dai granular_scopes",
                    pageTargetIds.size());
            for (String pageId : pageTargetIds) {
                try {
                    JsonNode page = graphClient.get(
                            "/" + pageId,
                            accessToken,
                            Map.of("fields", pageFields()),
                            "lettura Pagina autorizzata " + pageId
                    );
                    if (page.hasNonNull("id")) {
                        pages.add(page);
                    }
                } catch (MetaGraphApiException ex) {
                    log.info("Meta OAuth: target {} non leggibile come Pagina: {}", pageId, ex.getMessage());
                }
            }
            if (!pages.isEmpty()) {
                return List.copyOf(pages);
            }
        }

        try {
            JsonNode me = graphClient.get(
                    "/me",
                    accessToken,
                    Map.of("fields", "accounts.limit(100){" + pageFields() + "}"),
                    "elenco risorse Pagine autorizzate"
            );
            me.path("accounts").path("data").forEach(pages::add);
        } catch (MetaGraphApiException ex) {
            log.info("Meta OAuth: espansione /me?fields=accounts non disponibile: {}", ex.getMessage());
        }
        return List.copyOf(pages);
    }

    private String pageFields() {
        return "id,name,access_token,tasks,"
                + "instagram_business_account{id,username,name},"
                + "connected_instagram_account{id,username,name}";
    }

    private SelectedAssets selectAssets(List<JsonNode> pages, MetaTokenClient.DebugToken debug) {
        List<SelectedAssets> candidates = new ArrayList<>();
        for (JsonNode page : pages) {
            JsonNode instagram = page.path("instagram_business_account");
            if (instagram.isMissingNode() || instagram.isNull() || !instagram.hasNonNull("id")) {
                instagram = page.path("connected_instagram_account");
            }
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
            List<String> pageTargets = debug == null ? List.of() : debug.targetIds("pages_show_list");
            List<String> instagramTargets = debug == null ? List.of() : debug.targetIds("instagram_basic");
            throw new IllegalStateException(
                    "Meta non ha restituito alcuna Pagina autorizzata con un account Instagram professionale collegato. "
                            + "Pagine lette=" + pages.size()
                            + ", target Pagine=" + pageTargets
                            + ", target Instagram=" + instagramTargets
                            + ". Verifica che la Pagina e l'account Instagram siano collegati e selezionati nel login."
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
