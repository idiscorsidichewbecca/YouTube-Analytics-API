package it.chewbecca.youtubeanalytics.meta.exception;

public class MetaNotConnectedException extends RuntimeException {
    public MetaNotConnectedException() {
        this("Facebook e Instagram non sono ancora collegati. Apri /admin/meta/connect con autenticazione Basic.");
    }

    public MetaNotConnectedException(String message) {
        super(message);
    }
}
