package it.chewbecca.youtubeanalytics.exception;

public class YoutubeNotConnectedException extends RuntimeException {

    public YoutubeNotConnectedException() {
        super("Il canale YouTube non è ancora collegato. Apri /admin/youtube/connect con autenticazione Basic.");
    }
}
