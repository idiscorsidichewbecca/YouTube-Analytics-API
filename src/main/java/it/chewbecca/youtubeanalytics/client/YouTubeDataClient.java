package it.chewbecca.youtubeanalytics.client;

import com.fasterxml.jackson.databind.JsonNode;
import it.chewbecca.youtubeanalytics.oauth.GoogleAccessTokenService;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class YouTubeDataClient {

    private static final String BASE_URL = "https://www.googleapis.com/youtube/v3";
    private static final int MAX_VIDEO_IDS_PER_REQUEST = 50;

    private final RestClient restClient;
    private final GoogleAccessTokenService accessTokenService;

    public YouTubeDataClient(RestClient googleRestClient, GoogleAccessTokenService accessTokenService) {
        this.restClient = googleRestClient;
        this.accessTokenService = accessTokenService;
    }

    public List<ChannelMetadata> getOwnedChannels(String accessToken) {
        URI uri = UriComponentsBuilder.fromUriString(BASE_URL + "/channels")
                .queryParam("part", "id,snippet,statistics,contentDetails")
                .queryParam("mine", true)
                .queryParam("maxResults", 50)
                .build()
                .encode()
                .toUri();
        JsonNode root = get(uri, accessToken, "channels.list mine=true");
        List<ChannelMetadata> result = new ArrayList<>();
        for (JsonNode item : root.path("items")) {
            result.add(toChannelMetadata(item));
        }
        return result;
    }

    public ChannelMetadata getChannel(String channelId) {
        String accessToken = accessTokenService.getValidAccessToken();
        URI uri = UriComponentsBuilder.fromUriString(BASE_URL + "/channels")
                .queryParam("part", "id,snippet,statistics,contentDetails")
                .queryParam("id", channelId)
                .build()
                .encode()
                .toUri();
        JsonNode root = get(uri, accessToken, "channels.list id=" + channelId);
        JsonNode item = firstItem(root);
        if (item == null) {
            throw new GoogleApiException(404, "channels.list", "Canale non trovato: " + channelId);
        }
        return toChannelMetadata(item);
    }

    public List<VideoMetadata> getAllUploadedVideos(String uploadsPlaylistId) {
        return getAllUploadedVideos(uploadsPlaylistId, false);
    }

    public List<VideoMetadata> getOwnedUploadsForThumbnailReview(String uploadsPlaylistId) {
        return getAllUploadedVideos(uploadsPlaylistId, true);
    }

    private List<VideoMetadata> getAllUploadedVideos(String uploadsPlaylistId, boolean includeFileDetails) {
        String accessToken = accessTokenService.getValidAccessToken();
        List<UploadReference> references = loadUploadReferences(uploadsPlaylistId, accessToken);
        if (references.isEmpty()) {
            return List.of();
        }

        Map<String, VideoMetadata> videosById = new LinkedHashMap<>();
        List<String> ids = references.stream().map(UploadReference::videoId).toList();
        for (int from = 0; from < ids.size(); from += MAX_VIDEO_IDS_PER_REQUEST) {
            int to = Math.min(from + MAX_VIDEO_IDS_PER_REQUEST, ids.size());
            for (VideoMetadata video : loadVideos(ids.subList(from, to), accessToken, includeFileDetails)) {
                videosById.put(video.videoId(), video);
            }
        }

        List<VideoMetadata> ordered = new ArrayList<>();
        for (UploadReference reference : references) {
            VideoMetadata video = videosById.get(reference.videoId());
            if (video != null) {
                ordered.add(video);
            } else {
                ordered.add(VideoMetadata.unavailable(reference.videoId(), reference.title(), reference.publishedAt()));
            }
        }
        return ordered;
    }

    public VideoMetadata getVideo(String videoId) {
        return getVideo(videoId, false);
    }

    public VideoMetadata getOwnedVideoForThumbnailReview(String videoId) {
        return getVideo(videoId, true);
    }

    private VideoMetadata getVideo(String videoId, boolean includeFileDetails) {
        String accessToken = accessTokenService.getValidAccessToken();
        List<VideoMetadata> videos = loadVideos(List.of(videoId), accessToken, includeFileDetails);
        return videos.isEmpty() ? null : videos.getFirst();
    }

    private List<UploadReference> loadUploadReferences(String playlistId, String accessToken) {
        List<UploadReference> result = new ArrayList<>();
        String pageToken = null;
        do {
            UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(BASE_URL + "/playlistItems")
                    .queryParam("part", "snippet,contentDetails,status")
                    .queryParam("playlistId", playlistId)
                    .queryParam("maxResults", 50);
            if (pageToken != null) {
                builder.queryParam("pageToken", pageToken);
            }
            JsonNode root = get(builder.build().encode().toUri(), accessToken, "playlistItems.list uploads");
            for (JsonNode item : root.path("items")) {
                String videoId = item.path("contentDetails").path("videoId").asText(null);
                if (videoId != null) {
                    result.add(new UploadReference(
                            videoId,
                            item.path("snippet").path("title").asText("Video non disponibile"),
                            parseInstant(item.path("contentDetails").path("videoPublishedAt").asText(null),
                                    item.path("snippet").path("publishedAt").asText(null))
                    ));
                }
            }
            pageToken = textOrNull(root.path("nextPageToken"));
        } while (pageToken != null);
        return result;
    }

    private List<VideoMetadata> loadVideos(List<String> videoIds, String accessToken, boolean includeFileDetails) {
        if (videoIds.isEmpty()) {
            return List.of();
        }
        URI uri = UriComponentsBuilder.fromUriString(BASE_URL + "/videos")
                .queryParam("part", includeFileDetails
                        ? "snippet,contentDetails,statistics,status,fileDetails"
                        : "snippet,contentDetails,statistics,status")
                .queryParam("id", String.join(",", videoIds))
                .queryParam("maxResults", 50)
                .build()
                .encode()
                .toUri();
        JsonNode root = get(uri, accessToken, "videos.list");
        List<VideoMetadata> result = new ArrayList<>();
        for (JsonNode item : root.path("items")) {
            result.add(toVideoMetadata(item));
        }
        return result;
    }

    private JsonNode get(URI uri, String accessToken, String operation) {
        return restClient.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .onStatus(status -> status.isError(), (request, response) -> {
                    String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                    throw new GoogleApiException(response.getStatusCode().value(), operation, body);
                })
                .body(JsonNode.class);
    }

    private ChannelMetadata toChannelMetadata(JsonNode item) {
        JsonNode snippet = item.path("snippet");
        JsonNode statistics = item.path("statistics");
        JsonNode contentDetails = item.path("contentDetails");
        return new ChannelMetadata(
                item.path("id").asText(),
                snippet.path("title").asText(),
                snippet.path("description").asText(""),
                textOrNull(snippet.path("customUrl")),
                thumbnail(snippet.path("thumbnails")),
                statistics.path("subscriberCount").asLong(0),
                statistics.path("hiddenSubscriberCount").asBoolean(false),
                statistics.path("viewCount").asLong(0),
                statistics.path("videoCount").asLong(0),
                contentDetails.path("relatedPlaylists").path("uploads").asText()
        );
    }

    private VideoMetadata toVideoMetadata(JsonNode item) {
        JsonNode snippet = item.path("snippet");
        JsonNode contentDetails = item.path("contentDetails");
        JsonNode statistics = item.path("statistics");
        JsonNode status = item.path("status");
        List<String> tags = new ArrayList<>();
        snippet.path("tags").forEach(tag -> tags.add(tag.asText()));

        Duration duration = parseDuration(contentDetails.path("duration").asText("PT0S"));
        return new VideoMetadata(
                item.path("id").asText(),
                snippet.path("title").asText(),
                snippet.path("description").asText(""),
                parseInstant(snippet.path("publishedAt").asText(null), null),
                duration,
                thumbnail(snippet.path("thumbnails")),
                Collections.unmodifiableList(tags),
                snippet.path("categoryId").asText(null),
                snippet.path("liveBroadcastContent").asText("none"),
                status.path("privacyStatus").asText("unknown"),
                status.path("madeForKids").asBoolean(false),
                status.path("selfDeclaredMadeForKids").asBoolean(false),
                statistics.path("viewCount").asLong(0),
                statistics.path("likeCount").asLong(0),
                statistics.path("commentCount").asLong(0),
                true,
                snippet.path("channelId").asText(null),
                parseInstant(status.path("publishAt").asText(null), null),
                contentDetails.path("hasCustomThumbnail").isBoolean()
                        ? contentDetails.path("hasCustomThumbnail").booleanValue() : null,
                displayAspectRatio(item.path("fileDetails").path("videoStreams").path(0))
        );
    }

    private static Double displayAspectRatio(JsonNode stream) {
        double ratio = stream.path("aspectRatio").asDouble(0);
        if (ratio > 0) return ratio;
        double height = stream.path("heightPixels").asDouble(0);
        double width = stream.path("widthPixels").asDouble(0);
        if (width <= 0 || height <= 0) return null;
        ratio = width / height;
        return switch (stream.path("rotation").asText("none")) {
            case "clockwise", "counterClockwise" -> 1 / ratio;
            case "other" -> null;
            default -> ratio;
        };
    }

    private Duration parseDuration(String value) {
        try {
            return Duration.parse(value);
        } catch (RuntimeException ex) {
            return Duration.ZERO;
        }
    }

    private Instant parseInstant(String primary, String fallback) {
        String value = primary != null && !primary.isBlank() ? primary : fallback;
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String thumbnail(JsonNode thumbnails) {
        for (String size : List.of("maxres", "standard", "high", "medium", "default")) {
            String url = textOrNull(thumbnails.path(size).path("url"));
            if (url != null) {
                return url;
            }
        }
        return null;
    }

    private JsonNode firstItem(JsonNode root) {
        JsonNode items = root.path("items");
        return items.isArray() && !items.isEmpty() ? items.get(0) : null;
    }

    private String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value.isBlank() ? null : value;
    }

    private record UploadReference(String videoId, String title, Instant publishedAt) {
    }

    public record ChannelMetadata(
            String channelId,
            String title,
            String description,
            String customUrl,
            String thumbnailUrl,
            long subscriberCount,
            boolean hiddenSubscriberCount,
            long lifetimeViews,
            long videoCount,
            String uploadsPlaylistId
    ) {
    }

    public record VideoMetadata(
            String videoId,
            String title,
            String description,
            Instant publishedAt,
            Duration duration,
            String thumbnailUrl,
            List<String> tags,
            String categoryId,
            String liveBroadcastContent,
            String privacyStatus,
            boolean madeForKids,
            boolean selfDeclaredMadeForKids,
            long lifetimeViews,
            long lifetimeLikes,
            long lifetimeComments,
            boolean available,
            String channelId,
            Instant scheduledPublishAt,
            Boolean hasCustomThumbnail,
            Double displayAspectRatio
    ) {
        public VideoMetadata(String videoId, String title, String description, Instant publishedAt, Duration duration,
                             String thumbnailUrl, List<String> tags, String categoryId, String liveBroadcastContent,
                             String privacyStatus, boolean madeForKids, boolean selfDeclaredMadeForKids,
                             long lifetimeViews, long lifetimeLikes, long lifetimeComments, boolean available) {
            this(videoId, title, description, publishedAt, duration, thumbnailUrl, tags, categoryId,
                    liveBroadcastContent, privacyStatus, madeForKids, selfDeclaredMadeForKids,
                    lifetimeViews, lifetimeLikes, lifetimeComments, available, null, null, null, null);
        }

        public static VideoMetadata unavailable(String videoId, String title, Instant publishedAt) {
            return new VideoMetadata(
                    videoId,
                    title,
                    "",
                    publishedAt,
                    Duration.ZERO,
                    null,
                    List.of(),
                    null,
                    "none",
                    "unknown",
                    false,
                    false,
                    0,
                    0,
                    0,
                    false
            );
        }

        public long durationSeconds() {
            return duration == null ? 0 : duration.toSeconds();
        }

        public String youtubeUrl() {
            return "https://www.youtube.com/watch?v=" + videoId;
        }
    }
}
