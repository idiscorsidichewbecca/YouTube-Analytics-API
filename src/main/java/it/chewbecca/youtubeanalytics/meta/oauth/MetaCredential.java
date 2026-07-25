package it.chewbecca.youtubeanalytics.meta.oauth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "meta_credentials")
public class MetaCredential {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(name = "system_user_access_token", nullable = false, length = 8192)
    private String encryptedSystemUserAccessToken;

    @Column(name = "page_access_token", nullable = false, length = 8192)
    private String encryptedPageAccessToken;

    @Column(name = "access_token_expires_at", nullable = false)
    private Instant accessTokenExpiresAt;

    @Column(name = "token_type", length = 50)
    private String tokenType;

    @Column(name = "scopes", length = 4000)
    private String scopes;

    @Column(name = "business_id", length = 100)
    private String businessId;

    @Column(name = "business_name", length = 500)
    private String businessName;

    @Column(name = "page_id", nullable = false, length = 100)
    private String pageId;

    @Column(name = "page_name", nullable = false, length = 500)
    private String pageName;

    @Column(name = "instagram_account_id", nullable = false, length = 100)
    private String instagramAccountId;

    @Column(name = "instagram_username", nullable = false, length = 500)
    private String instagramUsername;

    @Column(name = "connected_at", nullable = false)
    private Instant connectedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected MetaCredential() {
    }

    public static MetaCredential create(String encryptedSystemUserAccessToken,
                                        String encryptedPageAccessToken,
                                        Instant accessTokenExpiresAt,
                                        String tokenType,
                                        String scopes,
                                        String businessId,
                                        String businessName,
                                        String pageId,
                                        String pageName,
                                        String instagramAccountId,
                                        String instagramUsername) {
        MetaCredential credential = new MetaCredential();
        credential.id = SINGLETON_ID;
        credential.reconnect(
                encryptedSystemUserAccessToken,
                encryptedPageAccessToken,
                accessTokenExpiresAt,
                tokenType,
                scopes,
                businessId,
                businessName,
                pageId,
                pageName,
                instagramAccountId,
                instagramUsername
        );
        return credential;
    }

    public void reconnect(String encryptedSystemUserAccessToken,
                          String encryptedPageAccessToken,
                          Instant accessTokenExpiresAt,
                          String tokenType,
                          String scopes,
                          String businessId,
                          String businessName,
                          String pageId,
                          String pageName,
                          String instagramAccountId,
                          String instagramUsername) {
        this.encryptedSystemUserAccessToken = encryptedSystemUserAccessToken;
        this.encryptedPageAccessToken = encryptedPageAccessToken;
        this.accessTokenExpiresAt = accessTokenExpiresAt;
        this.tokenType = tokenType;
        this.scopes = scopes;
        this.businessId = businessId;
        this.businessName = businessName;
        this.pageId = pageId;
        this.pageName = pageName;
        this.instagramAccountId = instagramAccountId;
        this.instagramUsername = instagramUsername;
        this.connectedAt = Instant.now();
        this.updatedAt = this.connectedAt;
    }

    public String getEncryptedSystemUserAccessToken() { return encryptedSystemUserAccessToken; }
    public String getEncryptedPageAccessToken() { return encryptedPageAccessToken; }
    public Instant getAccessTokenExpiresAt() { return accessTokenExpiresAt; }
    public String getTokenType() { return tokenType; }
    public String getScopes() { return scopes; }
    public String getBusinessId() { return businessId; }
    public String getBusinessName() { return businessName; }
    public String getPageId() { return pageId; }
    public String getPageName() { return pageName; }
    public String getInstagramAccountId() { return instagramAccountId; }
    public String getInstagramUsername() { return instagramUsername; }
    public Instant getConnectedAt() { return connectedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
