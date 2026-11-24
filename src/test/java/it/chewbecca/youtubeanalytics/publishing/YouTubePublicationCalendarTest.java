package it.chewbecca.youtubeanalytics.publishing;

import it.chewbecca.youtubeanalytics.oauth.GoogleAccessTokenService;
import it.chewbecca.youtubeanalytics.oauth.YoutubeCredential;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class YouTubePublicationCalendarTest {
    @Test
    void ownerCalendarUsesScheduledDatesAndIgnoresPrivateDrafts() {
        var tokens = mock(GoogleAccessTokenService.class);
        var credential = mock(YoutubeCredential.class);
        when(tokens.getValidAccessToken()).thenReturn("test-token");
        when(tokens.requireCredential()).thenReturn(credential);
        when(credential.getUploadsPlaylistId()).thenReturn("UUexample");
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var calendar = new YouTubePublicationCalendar(builder.build(), tokens);

        server.expect(queryParam("playlistId", "UUexample"))
                .andRespond(withSuccess("""
                        {"items":[
                          {"contentDetails":{"videoId":"scheduled"}},
                          {"contentDetails":{"videoId":"privateDraft"}},
                          {"contentDetails":{"videoId":"premiere"}}
                        ]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(queryParam("part", "snippet,status,liveStreamingDetails"))
                .andRespond(withSuccess("""
                        {"items":[
                          {"id":"scheduled","status":{"privacyStatus":"private","publishAt":"2026-11-26T18:00:00Z"},
                           "snippet":{"publishedAt":"2026-11-20T09:00:00Z"}},
                          {"id":"privateDraft","status":{"privacyStatus":"private"},
                           "snippet":{"publishedAt":"2026-11-26T09:00:00Z"}},
                          {"id":"premiere","status":{"privacyStatus":"public"},
                           "liveStreamingDetails":{"scheduledStartTime":"2026-11-27T18:00:00Z"}}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        var entries = calendar.between(Instant.parse("2026-11-26T00:00:00Z"),
                Instant.parse("2026-11-28T00:00:00Z"));
        assertThat(entries).extracting(YouTubePublicationCalendar.Publication::videoId)
                .containsExactly("scheduled", "premiere");
        assertThat(entries).extracting(YouTubePublicationCalendar.Publication::publishAt)
                .containsExactly(Instant.parse("2026-11-26T18:00:00Z"), Instant.parse("2026-11-27T18:00:00Z"));
        server.verify();
    }
}
