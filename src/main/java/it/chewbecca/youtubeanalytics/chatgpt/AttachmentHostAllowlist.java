package it.chewbecca.youtubeanalytics.chatgpt;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class AttachmentHostAllowlist {
    private static final Pattern REGIONAL_AZURE = Pattern.compile(
            "oaisdmntpr[a-z0-9]{1,14}\\.blob\\.core\\.windows\\.net");
    private final JdbcTemplate jdbc;

    public AttachmentHostAllowlist(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    static boolean isCandidate(String host) {
        return host != null && REGIONAL_AZURE.matcher(host).matches();
    }

    boolean contains(String hostname) {
        if (!isCandidate(hostname)) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT CASE WHEN COUNT(*) > 0 THEN TRUE ELSE FALSE END FROM attachment_host_allowlist WHERE hostname = ?",
                Boolean.class, hostname));
    }

    public List<Map<String,Object>> list() {
        return jdbc.queryForList("SELECT hostname, approved_at, approved_by, evidence FROM attachment_host_allowlist ORDER BY hostname");
    }

    public boolean approve(String hostname, String confirmation, String evidence, String actor) {
        // Do not accept URLs, wildcard patterns, arbitrary Azure accounts or implicit approvals.
        String host = hostname == null ? "" : hostname.toLowerCase(Locale.ROOT);
        if (!isCandidate(host) || !("AUTORIZZO HOST " + host).equals(confirmation))
            throw new IllegalArgumentException("Richiesto hostname regionale esatto e AUTORIZZO HOST <hostname>");
        if (evidence == null || evidence.isBlank() || evidence.length() > 500
                || evidence.contains("://") || evidence.indexOf('?') >= 0
                || evidence.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Indica una breve evidenza verificata, senza URL o credenziali");
        if (actor == null || actor.isBlank() || actor.length() > 255)
            throw new IllegalArgumentException("Amministratore non valido");
        try {
            jdbc.update("INSERT INTO attachment_host_allowlist (hostname, approved_by, evidence) VALUES (?, ?, ?)",
                    host, actor, evidence);
            return true;
        } catch (DuplicateKeyException alreadyApproved) {
            return false;
        }
    }
}
