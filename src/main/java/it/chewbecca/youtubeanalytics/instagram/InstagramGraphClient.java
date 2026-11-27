package it.chewbecca.youtubeanalytics.instagram;

import com.fasterxml.jackson.databind.JsonNode;
import it.chewbecca.youtubeanalytics.meta.client.MetaGraphClient;
import it.chewbecca.youtubeanalytics.meta.oauth.MetaAccessTokenService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name="app.instagram.enabled", havingValue="true")
public class InstagramGraphClient {
    private final MetaGraphClient graph;
    private final MetaAccessTokenService tokens;
    public InstagramGraphClient(MetaGraphClient graph, MetaAccessTokenService tokens) {
        this.graph=graph; this.tokens=tokens;
    }
    String account() { return safeId(tokens.requireCredential().getInstagramAccountId()); }
    public record Connection(String accountId, String username, boolean verified, boolean publishPermission) {}
    public Connection connection() { return connection(account(),false); }
    public Connection requirePublishingConnection() { return connection(account(),true); }
    public Connection requirePublishingConnection(String expected) { return connection(expected,true); }
    private Connection connection(String expected, boolean publish) {
        String token=token(expected,publish);
        var credential=tokens.requireCredential();
        if(!expected.equals(credential.getInstagramAccountId()))throw new GraphFailure("META_ACCOUNT_CHANGED");
        boolean allowed=MetaAccessTokenService.hasInstagramPublishPermission(credential);
        var data=get("/"+safeId(expected),token,Map.of("fields","id,username"));
        if(!expected.equals(data.path("id").asText()))throw new GraphFailure("META_ACCOUNT_MISMATCH");
        return new Connection(expected,data.path("username").asText(""),true,allowed);
    }
    /** Read-only capability probe: does not create containers or publish stories. */
    public Map<String,Object> probeStories() {
        try {
            String id=account();
            var data=get("/"+safeId(id)+"/stories",token(id,false),Map.of("fields","id","limit","1"));
            if(data==null||!data.path("data").isArray())return Map.of("ok",false,"reason","INVALID_RESPONSE");
            return Map.of("ok",true,"returnedCount",data.path("data").size());
        } catch(GraphFailure e) {
            return Map.of("ok",false,"reason",e.getMessage());
        } catch(RuntimeException e) {
            return Map.of("ok",false,"reason","CONNECTION_OR_CREDENTIAL_ERROR");
        }
    }

    public String createStory(String expected,String imageUrl) {
        return id(post("/"+safeId(expected)+"/media",token(expected,true),Map.of("media_type","STORIES","image_url",imageUrl)));
    }
    public String createReel(String account, String videoUrl, String coverUrl, String caption) {
        return id(post("/" + safeId(account) + "/media", token(account, true), Map.of(
                "media_type", "REELS", "video_url", videoUrl, "cover_url", coverUrl,
                "caption", caption, "share_to_feed", "true")));
    }
    public List<JsonNode> recentMedia(String account) {
        String accessToken = token(account, false);
        return graph.getAll("/" + safeId(account) + "/media", accessToken, Map.of(
                "fields", "timestamp,media_product_type,like_count,comments_count"),
                50, "Calendario Reel Instagram");
    }
    public String containerStatus(String expected,String id) {
        return get("/"+safeId(id),token(expected,true),Map.of("fields","status_code")).path("status_code").asText("UNKNOWN");
    }
    public String publish(String expected,String id) {
        return id(post("/"+safeId(expected)+"/media_publish",token(expected,true),Map.of("creation_id",safeId(id))));
    }
    public Long reach(String expected,String mediaId) {
        var response=get("/"+safeId(mediaId)+"/insights",token(expected,false),Map.of("metric","reach"));
        for(var metric:response.path("data")){
            if(!"reach".equals(metric.path("name").asText()))continue;
            var value=metric.path("values").path(0).path("value");
            if(!value.isIntegralNumber())value=metric.path("total_value").path("value");
            if(value.isIntegralNumber()&&value.asLong()>=0)return value.asLong();
        }
        return null;
    }
    private String token(String expected,boolean publish) {
        try{return tokens.getPageAccessTokenForInstagram(expected,publish);}
        catch(RuntimeException e){throw new GraphFailure(publish?"META_CONNECTION_OR_PUBLISH_PERMISSION_REQUIRED":"META_CONNECTION_REQUIRED");}
    }
    private JsonNode get(String path,String token,Map<String,?> params){
        try{return graph.get(path,token,params,"Instagram Expert Quiz");}
        catch(RuntimeException e){throw new GraphFailure("META_READ_FAILED_CHECK_CONNECTION_AND_PERMISSIONS");}
    }
    private JsonNode post(String path,String token,Map<String,String> params){
        try{return graph.post(path,token,params,"Instagram Expert Quiz");}
        catch(RuntimeException e){throw new GraphFailure("META_WRITE_FAILED_CHECK_CONNECTION_AND_PERMISSIONS");}
    }
    private static String id(JsonNode data){return safeId(data.path("id").asText(""));}
    private static String safeId(String id){if(id==null||!id.matches("[0-9]{1,100}"))throw new GraphFailure("META_INVALID_ID");return id;}
    public static class GraphFailure extends RuntimeException { GraphFailure(String code){super(code);} }
}
