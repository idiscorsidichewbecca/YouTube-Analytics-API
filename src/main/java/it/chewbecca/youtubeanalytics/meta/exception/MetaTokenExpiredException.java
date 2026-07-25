package it.chewbecca.youtubeanalytics.meta.exception;

public class MetaTokenExpiredException extends RuntimeException {
    public MetaTokenExpiredException() {
        super("Il token Meta è scaduto o è stato revocato. Ripeti il collegamento da /admin/meta/connect.");
    }

    public MetaTokenExpiredException(String message) {
        super(message);
    }
}
