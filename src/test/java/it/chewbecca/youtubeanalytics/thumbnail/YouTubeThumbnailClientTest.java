package it.chewbecca.youtubeanalytics.thumbnail;

import it.chewbecca.youtubeanalytics.oauth.GoogleAccessTokenService;
import it.chewbecca.youtubeanalytics.oauth.YoutubeCredential;
import it.chewbecca.youtubeanalytics.oauth.YoutubeOAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class YouTubeThumbnailClientTest {
    private final GoogleAccessTokenService tokens = mock(GoogleAccessTokenService.class);
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final YouTubeThumbnailClient client = new YouTubeThumbnailClient(builder.build(), tokens);

    @Test
    void prefersItalianManualCaptionsAndSkipsUnsuitableTracks() {
        var credential = mock(YoutubeCredential.class);
        when(tokens.getValidAccessToken()).thenReturn("test-token");
        when(tokens.requireCredential()).thenReturn(credential);
        when(credential.getScopes()).thenReturn(YoutubeOAuthService.PLAYLIST_SCOPE);
        server.expect(requestTo("https://www.googleapis.com/youtube/v3/captions?part=snippet&videoId=abcdefghijk"))
                .andRespond(withSuccess("""
                        {"items":[
                         {"id":"generated","snippet":{"language":"it","trackKind":"ASR","status":"serving"}},
                         {"id":"processing","snippet":{"language":"it","trackKind":"standard","status":"syncing"}},
                         {"id":"manual","snippet":{"language":"it-IT","trackKind":"standard","status":"serving"}}
                        ]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://www.googleapis.com/youtube/v3/captions/manual?tfmt=vtt"))
                .andRespond(withSuccess("WEBVTT\n\nTitolo e parlato", MediaType.TEXT_PLAIN));

        assertThat(client.caption("abcdefghijk").webVtt()).contains("Titolo e parlato");
        server.verify();
    }

    @Test
    void postsOnlySelectedJpegToTheSpecifiedYoutubeVideo() {
        byte[] image = new byte[]{1, 3, 7};
        when(tokens.getValidAccessToken()).thenReturn("test-token");
        server.expect(requestTo("https://www.googleapis.com/upload/youtube/v3/thumbnails/set?videoId=abcdefghijk&uploadType=media"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(content().bytes(image))
                .andRespond(withSuccess("{\"items\":[{}]}", MediaType.APPLICATION_JSON));
        client.setThumbnail("abcdefghijk", image);
        server.verify();
    }
}
