package it.chewbecca.youtubeanalytics.service;

import it.chewbecca.youtubeanalytics.client.YouTubeDataClient;
import it.chewbecca.youtubeanalytics.oauth.GoogleAccessTokenService;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class YoutubeCatalogService {

    private final YouTubeDataClient dataClient;
    private final GoogleAccessTokenService accessTokenService;

    public YoutubeCatalogService(YouTubeDataClient dataClient, GoogleAccessTokenService accessTokenService) {
        this.dataClient = dataClient;
        this.accessTokenService = accessTokenService;
    }

    @Cacheable("channelMetadata")
    public YouTubeDataClient.ChannelMetadata channel() {
        String channelId = accessTokenService.requireCredential().getChannelId();
        return dataClient.getChannel(channelId);
    }

    @Cacheable("videoCatalog")
    public List<YouTubeDataClient.VideoMetadata> videos() {
        String uploadsPlaylistId = accessTokenService.requireCredential().getUploadsPlaylistId();
        return dataClient.getAllUploadedVideos(uploadsPlaylistId);
    }

    public YouTubeDataClient.VideoMetadata video(String videoId) {
        return videos().stream()
                .filter(video -> video.videoId().equals(videoId))
                .findFirst()
                .orElse(null);
    }
}
