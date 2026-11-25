package it.chewbecca.youtubeanalytics.instagram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(name="app.instagram.enabled", havingValue="true")
public class InstagramGraphClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json;
    private final String version, account, token;
    public InstagramGraphClient(ObjectMapper json, @Value("${app.instagram.graph-version:v27.0}") String version,
            @Value("${app.instagram.account-id}") String account, @Value("${app.instagram.access-token}") String token) {
        if (!version.matches("v[0-9]+\\.[0-9]+") || !account.matches("[0-9]{5,64}") || token.isBlank()) throw new IllegalArgumentException("Configurazione Instagram non valida");
        this.json=json; this.version=version; this.account=account; this.token=token;
    }
    String account() { return account; }
    public record Connection(String accountId, String username, boolean verified) {}
    public Connection connection() {
        var data = request("GET", "/me?fields=user_id,username", Map.of());
        String id=data.path("user_id").asText(data.path("id").asText(""));
        if (!account.equals(id)) throw new IllegalStateException("Il token non appartiene all'account configurato");
        return new Connection(id,data.path("username").asText(""),true);
    }
    public String createStory(String imageUrl) { return id(request("POST", "/"+account+"/media", Map.of("media_type","STORIES","image_url",imageUrl))); }
    public String containerStatus(String id) { return request("GET", "/"+safeId(id)+"?fields=status_code", Map.of()).path("status_code").asText("UNKNOWN"); }
    public String publish(String id) { return id(request("POST", "/"+account+"/media_publish", Map.of("creation_id",safeId(id)))); }
    public Long reach(String mediaId) {
        var response=request("GET", "/"+safeId(mediaId)+"/insights?metric=reach",Map.of());
        for (var metric:response.path("data")) {
            if (!"reach".equals(metric.path("name").asText())) continue;
            var value=metric.path("values").path(0).path("value");
            if (!value.isIntegralNumber()) value=metric.path("total_value").path("value");
            if (value.isIntegralNumber() && value.asLong()>=0) return value.asLong();
        }
        return null;
    }
    private JsonNode request(String method,String path, Map<String,String> fields) {
        var builder=HttpRequest.newBuilder(URI.create("https://graph.instagram.com/"+version+path)).timeout(Duration.ofSeconds(40)).header("Authorization","Bearer "+token).header("Accept","application/json");
        if (method.equals("POST")) builder.header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(fields.entrySet().stream().map(e -> enc(e.getKey())+"="+enc(e.getValue())).collect(Collectors.joining("&"))));
        try {
            var result=http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
            var body=json.readTree(result.body());
            if (result.statusCode()/100!=2 || body.has("error")) throw new GraphFailure("META_HTTP_"+result.statusCode()+"_CODE_"+body.path("error").path("code").asInt(0));
            return body;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new GraphFailure("META_INTERRUPTED"); }
        catch (java.io.IOException e) { throw new GraphFailure("META_NETWORK_OR_RESPONSE_ERROR"); }
    }
    private static String enc(String s) { return URLEncoder.encode(s,StandardCharsets.UTF_8); }
    private static String id(JsonNode data) { return safeId(data.path("id").asText("")); }
    private static String safeId(String id) { if (id==null || !id.matches("[0-9]{1,64}")) throw new GraphFailure("META_INVALID_ID"); return id; }
    public static class GraphFailure extends RuntimeException { GraphFailure(String code) { super(code); } }
}
