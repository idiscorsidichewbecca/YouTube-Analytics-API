package it.chewbecca.youtubeanalytics.api.model;

public enum TrendGranularity {
    DAY("day"),
    MONTH("month");

    private final String analyticsDimension;

    TrendGranularity(String analyticsDimension) {
        this.analyticsDimension = analyticsDimension;
    }

    public String analyticsDimension() {
        return analyticsDimension;
    }
}
