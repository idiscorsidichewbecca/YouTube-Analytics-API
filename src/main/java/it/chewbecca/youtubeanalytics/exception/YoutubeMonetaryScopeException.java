package it.chewbecca.youtubeanalytics.exception;

public class YoutubeMonetaryScopeException extends RuntimeException {

    public YoutubeMonetaryScopeException() {
        super("Il collegamento YouTube non include il permesso per i dati economici. Disconnetti e ricollega il canale tramite /admin/youtube/connect.");
    }
}
