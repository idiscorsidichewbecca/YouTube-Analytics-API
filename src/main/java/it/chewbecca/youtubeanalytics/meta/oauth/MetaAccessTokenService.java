package it.chewbecca.youtubeanalytics.meta.oauth;

import it.chewbecca.youtubeanalytics.meta.config.MetaProperties;
import it.chewbecca.youtubeanalytics.meta.exception.MetaNotConnectedException;
import it.chewbecca.youtubeanalytics.meta.exception.MetaTokenExpiredException;
import it.chewbecca.youtubeanalytics.oauth.CredentialCryptoService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Service
public class MetaAccessTokenService {

    private static final Duration EXPIRY_SKEW = Duration.ofMinutes(5);

    private final MetaCredentialRepository repository;
    private final CredentialCryptoService cryptoService;
    private final MetaProperties properties;

    public MetaAccessTokenService(MetaCredentialRepository repository,
                                  CredentialCryptoService cryptoService,
                                  MetaProperties properties) {
        this.repository = repository;
        this.cryptoService = cryptoService;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public String getSystemUserAccessToken() {
        MetaCredential credential = requireCredential();
        ensureValid(credential);
        return cryptoService.decrypt(credential.getEncryptedSystemUserAccessToken());
    }

    @Transactional(readOnly = true)
    public String getPageAccessToken() {
        MetaCredential credential = requireCredential();
        ensureValid(credential);
        return cryptoService.decrypt(credential.getEncryptedPageAccessToken());
    }

    @Transactional(readOnly = true)
    public MetaCredential requireCredential() {
        if (!properties.enabled()) {
            throw new MetaNotConnectedException(
                    "L'integrazione Meta è disabilitata. Imposta META_ENABLED=true e collega gli account da /admin/meta/connect."
            );
        }
        properties.requireConfigured();
        return repository.findById(MetaCredential.SINGLETON_ID)
                .orElseThrow(MetaNotConnectedException::new);
    }

    private void ensureValid(MetaCredential credential) {
        if (credential.getAccessTokenExpiresAt() == null
                || !credential.getAccessTokenExpiresAt().isAfter(Instant.now().plus(EXPIRY_SKEW))) {
            throw new MetaTokenExpiredException();
        }
    }
}
