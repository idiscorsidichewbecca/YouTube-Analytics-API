package it.chewbecca.youtubeanalytics.publishing;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.chewbecca.youtubeanalytics.oauth.GoogleAccessTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class YouTubePlaylistServiceTest {
    @Test void alreadyInPlaylistDoesNotInsertAgain() {
        var tokens = mock(GoogleAccessTokenService.class);
        when(tokens.getValidAccessToken()).thenReturn("test-token");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var client = new YouTubePlaylistService(builder.build(), tokens, new ObjectMapper(), "Lo sapevi?");
        server.expect(requestTo("https://www.googleapis.com/youtube/v3/playlists?part=snippet&mine=true&maxResults=50"))
                .andRespond(withSuccess("{\"items\":[{\"id\":\"PLxyz\",\"snippet\":{\"title\":\"Lo sapevi?\"}}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://www.googleapis.com/youtube/v3/playlistItems?part=id&playlistId=PLxyz&videoId=abcdefghijk"))
                .andRespond(withSuccess("{\"items\":[{\"id\":\"existing\"}]}", MediaType.APPLICATION_JSON));
        var result = client.ensureInTargetPlaylist("abcdefghijk");
        assertThat(result.status()).isEqualTo("ALREADY_PRESENT");
        server.verify();
    }

    @Test void missingVideoIsInsertedExactlyOnce() {
        var tokens = mock(GoogleAccessTokenService.class);
        when(tokens.getValidAccessToken()).thenReturn("test-token");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var client = new YouTubePlaylistService(builder.build(), tokens, new ObjectMapper(), "Lo sapevi?");
        server.expect(requestTo("https://www.googleapis.com/youtube/v3/playlists?part=snippet&mine=true&maxResults=50"))
                .andRespond(withSuccess("{\"items\":[{\"id\":\"PLxyz\",\"snippet\":{\"title\":\"Lo sapevi?\"}}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://www.googleapis.com/youtube/v3/playlistItems?part=id&playlistId=PLxyz&videoId=abcdefghijk"))
                .andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://www.googleapis.com/youtube/v3/playlistItems?part=snippet"))
                .andRespond(withSuccess("{\"id\":\"PLIabc\"}", MediaType.APPLICATION_JSON));
        var result = client.ensureInTargetPlaylist("abcdefghijk");
        assertThat(result.status()).isEqualTo("ADDED");
        server.verify();
    }
}
