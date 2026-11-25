package it.chewbecca.youtubeanalytics.community;

import com.fasterxml.jackson.databind.JsonNode;
import it.chewbecca.youtubeanalytics.client.YouTubeStudioClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static it.chewbecca.youtubeanalytics.client.YouTubeStudioClient.DATA;

@Component
public class YouTubeCommentsClient {
    private final YouTubeStudioClient google;

    public YouTubeCommentsClient(YouTubeStudioClient google) {
        this.google = google;
    }

    public Page threads(String channel, String cursor, int pageSize, String moderation) {
        Map<String, Object> query = new HashMap<>(Map.of("part", "snippet,replies", "allThreadsRelatedToChannelId",
                channel, "order", "time", "textFormat", "plainText", "maxResults", pageSize));
        if (!"published".equals(moderation)) query.put("moderationStatus", moderation);
        if (cursor != null && !cursor.isBlank()) query.put("pageToken", cursor);
        JsonNode response = google.get(DATA, "commentThreads", query);
        List<Thread> threads = new ArrayList<>();
        response.path("items").forEach(node -> threads.add(thread(node)));
        return new Page(threads, response.path("nextPageToken").asText(null));
    }

    public Thread threadById(String id) {
        JsonNode response = google.get(DATA, "commentThreads", Map.of("part", "snippet,replies", "id", id,
                "textFormat", "plainText"));
        JsonNode node = response.path("items").path(0);
        return node.isMissingNode() ? null : thread(node);
    }

    public List<Comment> replies(Thread thread, boolean fresh) {
        if (!fresh && thread.replyCount() == thread.embedded().size()) return thread.embedded();
        var result = new java.util.LinkedHashMap<String, Comment>();
        if (!fresh) thread.embedded().forEach(comment -> result.put(comment.id(), comment));
        Map<String, Object> query = new HashMap<>(Map.of("part", "snippet", "textFormat", "plainText",
                "parentId", thread.root().id(), "maxResults", 100));
        String next;
        do {
            JsonNode page = google.get(DATA, "comments", query);
            page.path("items").forEach(node -> {
                var reply = comment(node);
                result.put(reply.id(), reply);
            });
            next = page.path("nextPageToken").asText(null);
            if (next != null) query.put("pageToken", next);
        } while (next != null && !next.isBlank());
        return List.copyOf(result.values());
    }

    public String insertReply(String parent, String text) {
        return google.write(DATA, "comments", Map.of("part", "snippet"),
                Map.of("snippet", Map.of("parentId", parent, "textOriginal", text)), false, null)
                .path("id").asText(null);
    }

    private Thread thread(JsonNode item) {
        JsonNode snippet = item.path("snippet");
        List<Comment> embedded = new ArrayList<>();
        item.path("replies").path("comments").forEach(node -> embedded.add(comment(node)));
        return new Thread(item.path("id").asText(), snippet.path("channelId").asText(),
                snippet.path("videoId").asText(null), snippet.path("canReply").asBoolean(),
                snippet.path("totalReplyCount").asInt(), comment(snippet.path("topLevelComment")), embedded);
    }

    private Comment comment(JsonNode item) {
        JsonNode snippet = item.path("snippet");
        return new Comment(item.path("id").asText(), snippet.path("authorChannelId").path("value").asText(null),
                snippet.path("authorDisplayName").asText(""),
                snippet.path("textOriginal").asText(snippet.path("textDisplay").asText("")),
                Instant.parse(snippet.path("publishedAt").asText()), snippet.path("parentId").asText(null),
                snippet.path("moderationStatus").asText(null));
    }

    public record Comment(String id, String authorChannel, String author, String text, Instant publishedAt,
                          String parentId, String moderationStatus) {
        public boolean isPublishedOrUnspecified() {
            return moderationStatus == null || "published".equals(moderationStatus);
        }
    }
    public record Thread(String id, String channelId, String videoId, boolean canReply, int replyCount,
                         Comment root, List<Comment> embedded) {}
    public record Page(List<Thread> threads, String nextPageToken) {}
}
