package it.chewbecca.youtubeanalytics.mcp;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChewbeccaMcpMetadataControllerTest {
    @Test void publishesOnlyReadScopeAndOwnResource() {
        var metadata = new ChewbeccaMcpMetadataController("https://example.onrender.com/").protectedResource();
        assertEquals("https://example.onrender.com/mcp", metadata.get("resource"));
        assertEquals(java.util.List.of("https://example.onrender.com"), metadata.get("authorization_servers"));
        assertEquals(java.util.List.of("shorts:read"), metadata.get("scopes_supported"));
    }
}
