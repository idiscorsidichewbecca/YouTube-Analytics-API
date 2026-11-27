package it.chewbecca.youtubeanalytics.chatgpt;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Hashtable;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.regex.Pattern;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.InitialDirContext;

/**
 * Azure account names are not proof of ownership. Previously observed connector
 * accounts are trusted exactly; a new account must be explicitly configured or
 * directly referenced by the CNAME of the corresponding OpenAI-owned DNS name.
 */
final class OpenAiAzureFileHosts {
    private static final Set<String> OBSERVED = Set.of(
            "oaisdmntprdenmarkeast.blob.core.windows.net",
            "oaisdmntprpolandcentral.blob.core.windows.net",
            "oaisdmntprcentralus.blob.core.windows.net");
    private static final Pattern CANDIDATE = Pattern.compile(
            "oaisdmntpr([a-z0-9]{1,14})\\.blob\\.core\\.windows\\.net");
    private final BiPredicate<String,String> cnameVerifier;
    private final Cache<String,Boolean> verified = Caffeine.newBuilder()
            .maximumSize(64).expireAfterWrite(Duration.ofMinutes(5)).build();

    OpenAiAzureFileHosts() { this(OpenAiAzureFileHosts::hasDirectCname); }
    OpenAiAzureFileHosts(BiPredicate<String,String> cnameVerifier) {
        this.cnameVerifier = cnameVerifier;
    }

    boolean allows(String hostname) {
        String host = hostname.toLowerCase(Locale.ROOT);
        if (OBSERVED.contains(host)) return true;
        var match = CANDIDATE.matcher(host);
        if (!match.matches()) return false;
        if (Boolean.TRUE.equals(verified.getIfPresent(host))) return true;
        String trustedAlias = "sdmntpr" + match.group(1) + ".oaiusercontent.com.";
        try {
            if (!cnameVerifier.test(trustedAlias, host)) return false;
            verified.put(host, true);
            return true;
        } catch (RuntimeException e) {
            // Unknown or unavailable DNS never expands the allowlist.
            return false;
        }
    }

    private static boolean hasDirectCname(String alias, String expected) {
        var environment = new Hashtable<String,String>();
        environment.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
        environment.put(Context.PROVIDER_URL, "dns:");
        environment.put("com.sun.jndi.dns.timeout.initial", "1000");
        environment.put("com.sun.jndi.dns.timeout.retries", "1");
        InitialDirContext context = null;
        NamingEnumeration<?> records = null;
        try {
            context = new InitialDirContext(environment);
            var cname = context.getAttributes(alias, new String[]{"CNAME"}).get("CNAME");
            if (cname == null) return false;
            records = cname.getAll();
            while (records.hasMore()) {
                String target = String.valueOf(records.next()).toLowerCase(Locale.ROOT);
                if (target.endsWith(".")) target = target.substring(0,target.length()-1);
                if (expected.equals(target)) return true;
            }
            return false;
        } catch (NamingException | RuntimeException e) {
            return false;
        } finally {
            if (records != null) try { records.close(); } catch (NamingException ignored) {}
            if (context != null) try { context.close(); } catch (NamingException ignored) {}
        }
    }
}
