package it.chewbecca.youtubeanalytics.chatgpt;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/** Credenziale separata dall'API key di lettura del GPT storico. */
@Component
public class ChatGptBridgeAuthenticationFilter extends OncePerRequestFilter {
    private static final String PREFIX = "/internal/chatgpt/";
    private final ChatGptBridgeProperties properties;

    public ChatGptBridgeAuthenticationFilter(ChatGptBridgeProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!properties.enabled()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String header = request.getHeader("Authorization");
        String expected = properties.bearerKey();
        if (expected == null || expected.length() < 32 || header == null || !header.startsWith("Bearer ") ||
                !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                        header.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"Unauthorized\"}");
            return;
        }
        var authentication = new UsernamePasswordAuthenticationToken("chatgpt-site-bridge", null,
                List.of(new SimpleGrantedAuthority("ROLE_CHATGPT_PLUGIN")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try { chain.doFilter(request, response); }
        finally { SecurityContextHolder.clearContext(); }
    }
}
