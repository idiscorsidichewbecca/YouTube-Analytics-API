package it.chewbecca.youtubeanalytics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;

@EnableCaching
@ConfigurationPropertiesScan
@SpringBootApplication
public class YoutubeAnalyticsApplication {

    public static void main(String[] args) {
        SpringApplication.run(YoutubeAnalyticsApplication.class, args);
    }
}
