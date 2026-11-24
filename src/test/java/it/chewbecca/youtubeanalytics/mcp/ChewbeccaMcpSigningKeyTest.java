package it.chewbecca.youtubeanalytics.mcp;

import org.junit.jupiter.api.Test;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class ChewbeccaMcpSigningKeyTest {
    @Test void samePkcs8ValueProducesSamePublicKeyAcrossRestarts() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072);
        var initial = generator.generateKeyPair();
        String persisted = Base64.getEncoder().encodeToString(initial.getPrivate().getEncoded());
        var first = ChewbeccaMcpOAuthConfig.keyPairFromPkcs8Base64(persisted);
        var second = ChewbeccaMcpOAuthConfig.keyPairFromPkcs8Base64(persisted);
        assertArrayEquals(first.getPublic().getEncoded(), second.getPublic().getEncoded());
        assertEquals(((RSAPublicKey) initial.getPublic()).getModulus(),
                ((RSAPublicKey) first.getPublic()).getModulus());
    }
    @Test void emptyKeyIsRejected() {
        assertThrows(IllegalStateException.class,
                () -> ChewbeccaMcpOAuthConfig.keyPairFromPkcs8Base64(""));
    }
}
