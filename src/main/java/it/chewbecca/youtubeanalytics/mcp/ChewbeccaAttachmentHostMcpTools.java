package it.chewbecca.youtubeanalytics.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import it.chewbecca.youtubeanalytics.chatgpt.AttachmentHostAllowlist;
import it.chewbecca.youtubeanalytics.config.AppSecurityProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static it.chewbecca.youtubeanalytics.mcp.ChewbeccaStudioMcpSupport.*;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public class ChewbeccaAttachmentHostMcpTools {
    @Bean
    List<SyncToolSpecification> chewbeccaAttachmentHostTools(
            AttachmentHostAllowlist hosts, AppSecurityProperties security, ObjectMapper json,
            @Value("${app.mcp.public-url}") String publicUrl) {
        var support = new ChewbeccaStudioMcpSupport(json, publicUrl);
        return List.of(
                support.tool("chewbecca_list_attachment_hosts",
                        "Mostra gli host regionali approvati e persistiti. Solo amministratore; non scarica file.",
                        Map.of(), List.of(), Mode.READ, args -> {
                            requireOwner(security);
                            return out(Map.of("hosts", hosts.list()));
                        }),
                support.tool("chewbecca_approve_attachment_host",
                        APPROVAL_NOTICE + "Autorizza permanentemente un singolo hostname regionale Azure. "
                                + "Prima verifica indipendentemente che l'account appartenga a OpenAI; il nome o un errore di download non bastano. "
                                + "Richiedi al proprietario AUTORIZZO HOST <hostname> esatto. "
                                + "Non usare URL, wildcard o credenziali; non approvare automaticamente un host sconosciuto.",
                        Map.of("hostname", text(), "confirmation_text", text(), "evidence", text()),
                        List.of("hostname", "confirmation_text", "evidence"), Mode.APPROVAL, args -> {
                            String owner = requireOwner(security);
                            String hostname = required(args, "hostname");
                            boolean inserted = hosts.approve(hostname, required(args, "confirmation_text"),
                                    required(args, "evidence"), owner);
                            return out(Map.of("hostname", hostname, "approved", true, "newEntry", inserted,
                                    "restartRequired", false));
                        })
        );
    }

    private static String requireOwner(AppSecurityProperties security) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || security.adminUsername() == null
                || !security.adminUsername().equals(authentication.getName()))
            throw new IllegalStateException("Operazione riservata al proprietario amministratore");
        return authentication.getName();
    }
}
