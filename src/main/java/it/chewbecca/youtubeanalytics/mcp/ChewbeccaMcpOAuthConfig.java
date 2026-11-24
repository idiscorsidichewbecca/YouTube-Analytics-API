package it.chewbecca.youtubeanalytics.mcp;

import java.security.KeyPair;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** OAuth2 Authorization Server + JWT resource server: client pre-registrato, PKCE obbligatorio. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public class ChewbeccaMcpOAuthConfig {

    @Bean
    @Order(1)
    SecurityFilterChain authorizationServerChain(HttpSecurity http,
        @Value("${app.mcp.public-url}") String base) throws Exception {
        OAuth2AuthorizationServerConfigurer as = OAuth2AuthorizationServerConfigurer.authorizationServer();
        http.securityMatcher(as.getEndpointsMatcher())
            .with(as, Customizer.withDefaults())
            .authorizeHttpRequests(a -> a.anyRequest().authenticated())
            .exceptionHandling(e -> e.authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/login")))
            .csrf(csrf -> csrf.ignoringRequestMatchers(as.getEndpointsMatcher()));
        // Una sola audience consentita: il parametro RFC 8707 deve corrispondere alla risorsa MCP.
        String resource = base.replaceAll("/+$", "") + "/mcp";
        http.addFilterAfter(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                    FilterChain chain) throws ServletException, IOException {
                String path = request.getRequestURI();
                String requestedResource = request.getParameter("resource");
                // Un client puo' omettere resource: la audience dei JWT resta comunque
                // vincolata a /mcp dal token customizer e verificata dal JwtDecoder.
                // Se viene specificata una risorsa diversa, la richiesta e' rifiutata.
                if ((path.equals("/oauth2/authorize") || path.equals("/oauth2/token"))
                        && requestedResource != null && !resource.equals(requestedResource)) {
                    response.sendError(400, "Invalid OAuth resource");
                    return;
                }
                chain.doFilter(request, response);
            }
        }, SecurityContextHolderFilter.class);
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain mcpResourceChain(HttpSecurity http,
                                         @Value("${app.mcp.public-url}") String base) throws Exception {
        String metadata = base.replaceAll("/+$", "") + "/.well-known/oauth-protected-resource";
        return http.securityMatcher("/mcp", "/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp")
            .authorizeHttpRequests(a -> a
                .requestMatchers("/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp").permitAll()
                .anyRequest().hasAuthority("SCOPE_shorts:read"))
            .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()))
            .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> {
                res.setStatus(401);
                res.setHeader("WWW-Authenticate", "Bearer resource_metadata=\"" + metadata + "\", scope=\"shorts:read\"");
            }))
            .csrf(csrf -> csrf.disable())
            .build();
    }

    @Bean
    RegisteredClientRepository registeredClientRepository(@Value("${app.mcp.redirect-uri}") String redirect) {
        RegisteredClient client = RegisteredClient.withId(UUID.nameUUIDFromBytes("chewbecca-chatgpt".getBytes()).toString())
            .clientId("chewbecca-chatgpt")
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri(redirect)
            .scope("shorts:read")
            .clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(true).build())
            .tokenSettings(TokenSettings.builder().accessTokenTimeToLive(Duration.ofMinutes(30)).build())
            .build();
        return new InMemoryRegisteredClientRepository(client);
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings(@Value("${app.mcp.public-url}") String base) {
        if (!base.startsWith("https://")) throw new IllegalStateException("MCP_PUBLIC_URL deve essere HTTPS");
        return AuthorizationServerSettings.builder().issuer(base.replaceAll("/+$", "")).build();
    }

    /**
     * Stable key material is mandatory. Never generate an ephemeral signing key
     * on startup: that would invalidate tokens after each Render redeploy.
     * Keep the PKCS#8 DER Base64 value in Render's secret environment only.
     */
    @Bean
    KeyPair mcpSigningKeyPair(@Value("${app.mcp.signing-private-key-pkcs8-b64:}") String encoded) throws Exception {
        return keyPairFromPkcs8Base64(encoded);
    }

    static KeyPair keyPairFromPkcs8Base64(String encoded) throws Exception {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException("MCP_SIGNING_PRIVATE_KEY_PKCS8_B64 mancante: MCP non può essere abilitato");
        }
        byte[] pkcs8;
        try {
            pkcs8 = Base64.getDecoder().decode(encoded.replaceAll("\\s+", ""));
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("MCP_SIGNING_PRIVATE_KEY_PKCS8_B64 non è Base64 valido", ex);
        }
        var privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        if (!(privateKey instanceof RSAPrivateCrtKey rsa) || rsa.getModulus().bitLength() < 3072) {
            throw new IllegalStateException("Usa una chiave RSA PKCS#8 con almeno 3072 bit");
        }
        var publicKey = KeyFactory.getInstance("RSA").generatePublic(
                new RSAPublicKeySpec(rsa.getModulus(), rsa.getPublicExponent()));
        return new KeyPair(publicKey, privateKey);
    }

    @Bean
    JWKSource<SecurityContext> jwkSource(KeyPair mcpSigningKeyPair) throws Exception {
        RSAKey rsa = new RSAKey.Builder((RSAPublicKey) mcpSigningKeyPair.getPublic())
            .privateKey(mcpSigningKeyPair.getPrivate())
            .keyID(Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(mcpSigningKeyPair.getPublic().getEncoded()))).build();
        JWKSet jwkSet = new JWKSet(rsa);
        return (selector, context) -> selector.select(jwkSet);
    }

    @Bean
    JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource, KeyPair mcpSigningKeyPair,
                          @Value("${app.mcp.public-url}") String base) {
        String issuer = base.replaceAll("/+$", "");
        String audience = issuer + "/mcp";
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) mcpSigningKeyPair.getPublic()).build();
        var defaults = JwtValidators.createDefaultWithIssuer(issuer);
        decoder.setJwtValidator(jwt -> {
            var result = defaults.validate(jwt);
            if (result.hasErrors()) return result;
            return jwt.getAudience().contains(audience) ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "MCP audience non valida", null));
        });
        return decoder;
    }

    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> mcpTokenCustomizer(@Value("${app.mcp.public-url}") String base) {
        String audience = base.replaceAll("/+$", "") + "/mcp";
        return context -> {
            if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                context.getClaims().audience(List.of(audience));
            }
        };
    }
}
