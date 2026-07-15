package it.chewbecca.youtubeanalytics.oauth;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class OAuthStateStore {

    private static final Duration TTL = Duration.ofMinutes(10);

    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, Instant> states = new ConcurrentHashMap<>();

    public String create() {
        removeExpired();
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        states.put(state, Instant.now().plus(TTL));
        return state;
    }

    public boolean consume(String state) {
        if (state == null || state.isBlank()) {
            return false;
        }
        Instant expiration = states.remove(state);
        return expiration != null && expiration.isAfter(Instant.now());
    }

    private void removeExpired() {
        Instant now = Instant.now();
        states.entrySet().removeIf(entry -> entry.getValue().isBefore(now));
    }
}
