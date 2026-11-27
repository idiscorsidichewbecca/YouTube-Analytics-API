package it.chewbecca.youtubeanalytics.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GoogleDiagnosticReasonTest {
    @Test void extractsQuotaReasonWithoutReturningPayload(){
        assertEquals("quotaExceeded",YouTubeDataClient.safeGoogleReason(new GoogleApiException(403,"probe","{\"error\":{\"errors\":[{\"reason\":\"quotaExceeded\"}],\"message\":\"sensitive\"}}")));
    }
    @Test void rejectsUnexpectedCharacters(){
        assertEquals("GOOGLE_HTTP_403",YouTubeDataClient.safeGoogleReason(new GoogleApiException(403,"probe","{\"error\":{\"errors\":[{\"reason\":\"token=secret\"}]}}")));
    }
    @Test void handlesNonJsonBody(){
        assertEquals("GOOGLE_HTTP_503",YouTubeDataClient.safeGoogleReason(new GoogleApiException(503,"probe","not json")));
    }
}
