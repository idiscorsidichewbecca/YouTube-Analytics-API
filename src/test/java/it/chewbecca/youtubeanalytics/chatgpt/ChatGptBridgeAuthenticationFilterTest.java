package it.chewbecca.youtubeanalytics.chatgpt;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatGptBridgeAuthenticationFilterTest {
    private static final String SECRET = "c".repeat(64);

    @Test void blocksWrongKey() throws Exception {
        var filter = new ChatGptBridgeAuthenticationFilter(
                new ChatGptBridgeProperties(true, SECRET, List.of("files.openai.com")));
        var request = new MockHttpServletRequest("GET", "/internal/chatgpt/shorts/recommendation");
        request.addHeader("Authorization", "Bearer abc");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test void disablesEntireBridgeWhenConfigurationFlagIsFalse() throws Exception {
        var filter = new ChatGptBridgeAuthenticationFilter(
                new ChatGptBridgeProperties(false, SECRET, List.of("files.openai.com")));
        var request = new MockHttpServletRequest("GET", "/internal/chatgpt/shorts/recommendation");
        request.addHeader("Authorization", "Bearer " + SECRET);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test void acceptsValidDedicatedKeyOnly() throws Exception {
        var filter = new ChatGptBridgeAuthenticationFilter(
                new ChatGptBridgeProperties(true, SECRET, List.of("files.openai.com")));
        var request = new MockHttpServletRequest("GET", "/internal/chatgpt/shorts/recommendation");
        request.addHeader("Authorization", "Bearer " + SECRET);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
