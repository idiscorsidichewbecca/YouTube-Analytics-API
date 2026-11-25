package it.chewbecca.youtubeanalytics.performance;

import it.chewbecca.youtubeanalytics.client.YouTubeStudioClient;
import it.chewbecca.youtubeanalytics.oauth.GoogleAccessTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class YouTubeVideoChangesTest {
    @Test
    void updatesOnlySnippetAndPreservesWritableFields() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var tokens = mock(GoogleAccessTokenService.class);
        when(tokens.getValidAccessToken()).thenReturn("test");
        var videos = new YouTubeVideoChanges(new YouTubeStudioClient(builder.build(), tokens));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://www.googleapis.com/youtube/v3/videos?")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("id", "ABCDEFGHIJK")).andExpect(queryParam("part", "snippet"))
                .andRespond(withSuccess("""
                        {"items":[{"id":"ABCDEFGHIJK","etag":"revision","snippet":{
                        "title":"Vecchio","description":"Descrizione","channelId":"owner","categoryId":"22",
                        "tags":["A","B"],"defaultLanguage":"it","defaultAudioLanguage":"it",
                        "publishedAt":"2026-11-22T00:00:00Z","thumbnails":{}},
                        "status":{"privacyStatus":"private"}}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://www.googleapis.com/youtube/v3/videos?part=snippet"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header(HttpHeaders.IF_MATCH, "\"revision\""))
                .andExpect(content().json("""
                        {"id":"ABCDEFGHIJK","snippet":{"title":"Nuovo","description":"Descrizione",
                        "categoryId":"22","tags":["A","B"],"defaultLanguage":"it","defaultAudioLanguage":"it"}}
                        """, org.springframework.test.json.JsonCompareMode.STRICT))
                .andRespond(withSuccess("{\"id\":\"ABCDEFGHIJK\"}", MediaType.APPLICATION_JSON));

        var current = videos.read("owner", "ABCDEFGHIJK");
        videos.update("ABCDEFGHIJK", current, "TITLE", "Nuovo");
        server.verify();
    }
}
