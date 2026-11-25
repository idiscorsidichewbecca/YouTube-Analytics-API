package it.chewbecca.youtubeanalytics.oauth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "youtube_credentials")
public class YoutubeCredential {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id = SINGLETON_ID;

    @Column(name = "access_token", nullable = false, length = 4096)
    private String encryptedAccessToken;

    @Column(name = "refresh_token", nullable = false, length = 4096)
    private String encryptedRefreshToken;

    @Column(name = "access_token_expires_at", nullable = false)
    private Instant accessTokenExpiresAt;

    @Column(name = "token_type", length = 50)
    private String tokenType;

    @Column(name = "scopes", length = 2000)
    private String scopes;

    @Column(name = "channel_id", nullable = false, length = 100)
    private String channelId;

    @Column(name = "channel_title", nullable = false, length = 500)
    private String channelTitle;

    @Column(name = "uploads_playlist_id", nullable = false, length = 100)
    private String uploadsPlaylistId;

    @Column(name = "connected_at", nullable = false)
    private Instant connectedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected YoutubeCredential() {
    }

    public static YoutubeCredential create(String encryptedAccessToken,
                                           String encryptedRefreshToken,
                                           Instant accessTokenExpiresAt,
                                           String tokenType,
                                           String scopes,
                                           String channelId,
                                           String channelTitle,
                                           String uploadsPlaylistId) {
        YoutubeCredential credential = new YoutubeCredential();
        credential.id = SINGLETON_ID;
        credential.encryptedAccessToken = encryptedAccessToken;
        credential.encryptedRefreshToken = encryptedRefreshToken;
        credential.accessTokenExpiresAt = accessTokenExpiresAt;
        credential.tokenType = tokenType;
        credential.scopes = scopes;
        credential.channelId = channelId;
        credential.channelTitle = channelTitle;
        credential.uploadsPlaylistId = uploadsPlaylistId;
        credential.connectedAt = Instant.now();
        credential.updatedAt = credential.connectedAt;
        return credential;
    }


    public void reconnect(String encryptedAccessToken,
                          String encryptedRefreshToken,
                          Instant accessTokenExpiresAt,
                          String tokenType,
                          String scopes,
                          String channelId,
                          String channelTitle,
                          String uploadsPlaylistId) {
        this.encryptedAccessToken = encryptedAccessToken;
        if (encryptedRefreshToken != null && !encryptedRefreshToken.isBlank()) {
            this.encryptedRefreshToken = encryptedRefreshToken;
        }
        this.accessTokenExpiresAt = accessTokenExpiresAt;
        this.tokenType = tokenType;
        this.scopes = scopes;
        this.channelId = channelId;
        this.channelTitle = channelTitle;
        this.uploadsPlaylistId = uploadsPlaylistId;
        this.connectedAt = Instant.now();
        this.updatedAt = this.connectedAt;
    }

    public void updateTokens(String encryptedAccessToken,
                             String encryptedRefreshToken,
                             Instant accessTokenExpiresAt,
                             String tokenType,
                             String scopes) {
        this.encryptedAccessToken = encryptedAccessToken;
        if (encryptedRefreshToken != null && !encryptedRefreshToken.isBlank()) {
            this.encryptedRefreshToken = encryptedRefreshToken;
        }
        this.accessTokenExpiresAt = accessTokenExpiresAt;
        this.tokenType = tokenType;
        this.scopes = scopes;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getEncryptedAccessToken() {
        return encryptedAccessToken;
    }

    public String getEncryptedRefreshToken() {
        return encryptedRefreshToken;
    }

    public Instant getAccessTokenExpiresAt() {
        return accessTokenExpiresAt;
    }

    public String getTokenType() {
        return tokenType;
    }

    public String getScopes() {
        return scopes;
    }

    public String getChannelId() {
        return channelId;
    }

    public String getChannelTitle() {
        return channelTitle;
    }

    public String getUploadsPlaylistId() {
        return uploadsPlaylistId;
    }

    public Instant getConnectedAt() {
        return connectedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
