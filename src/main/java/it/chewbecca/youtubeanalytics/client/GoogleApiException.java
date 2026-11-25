package it.chewbecca.youtubeanalytics.client;

public class GoogleApiException extends RuntimeException {

    private final int statusCode;
    private final String responseBody;

    public GoogleApiException(int statusCode, String operation, String responseBody) {
        super("Errore Google API durante " + operation + " (HTTP " + statusCode + ")");
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getResponseBody() {
        return responseBody;
    }
}
