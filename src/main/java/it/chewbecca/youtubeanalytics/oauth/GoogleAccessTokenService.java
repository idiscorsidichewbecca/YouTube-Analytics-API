package it.chewbecca.youtubeanalytics.oauth;

import it.chewbecca.youtubeanalytics.exception.YoutubeNotConnectedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Service
public class GoogleAccessTokenService {

    private static final Duration REFRESH_SKEW = Duration.ofMinutes(2);

    private final YoutubeCredentialRepository repository;
    private final CredentialCryptoService cryptoService;
    private final GoogleTokenClient tokenClient;

    public GoogleAccessTokenService(YoutubeCredentialRepository repository,
                                    CredentialCryptoService cryptoService,
                                    GoogleTokenClient tokenClient) {
        this.repository = repository;
        this.cryptoService = cryptoService;
        this.tokenClient = tokenClient;
    }

    @Transactional
    public synchronized String getValidAccessToken() {
        YoutubeCredential credential = repository.findById(YoutubeCredential.SINGLETON_ID)
                .orElseThrow(YoutubeNotConnectedException::new);

        if (credential.getAccessTokenExpiresAt().isAfter(Instant.now().plus(REFRESH_SKEW))) {
            return cryptoService.decrypt(credential.getEncryptedAccessToken());
        }

        String refreshToken = cryptoService.decrypt(credential.getEncryptedRefreshToken());
        GoogleTokenClient.TokenResponse refreshed = tokenClient.refresh(refreshToken);
        credential.updateTokens(
                cryptoService.encrypt(refreshed.accessToken()),
                refreshed.refreshToken() == null ? null : cryptoService.encrypt(refreshed.refreshToken()),
                refreshed.expiresAt(),
                refreshed.tokenType(),
                refreshed.scope() == null ? credential.getScopes() : refreshed.scope()
        );
        repository.save(credential);
        return refreshed.accessToken();
    }

    @Transactional(readOnly = true)
    public YoutubeCredential requireCredential() {
        return repository.findById(YoutubeCredential.SINGLETON_ID)
                .orElseThrow(YoutubeNotConnectedException::new);
    }
}
