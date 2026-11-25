package it.chewbecca.youtubeanalytics.meta.client;

public class MetaGraphApiException extends RuntimeException {

    private final int statusCode;
    private final Integer graphCode;
    private final Integer graphSubcode;
    private final String operation;
    private final String responseBody;

    public MetaGraphApiException(int statusCode,
                                 Integer graphCode,
                                 Integer graphSubcode,
                                 String operation,
                                 String responseBody,
                                 String message) {
        super(message);
        this.statusCode = statusCode;
        this.graphCode = graphCode;
        this.graphSubcode = graphSubcode;
        this.operation = operation;
        this.responseBody = responseBody;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public Integer getGraphCode() {
        return graphCode;
    }

    public Integer getGraphSubcode() {
        return graphSubcode;
    }

    public String getOperation() {
        return operation;
    }

    public String getResponseBody() {
        return responseBody;
    }
}
