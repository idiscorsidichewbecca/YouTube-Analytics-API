package it.chewbecca.youtubeanalytics.instagram;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.*;
import java.util.Base64;

/** Only an unguessable capability URL for the single story currently being published is exposed. */
@RestController
@ConditionalOnProperty(name="app.instagram.enabled",havingValue="true")
public class InstagramMediaEndpoint {
    private final JdbcTemplate db;
    public InstagramMediaEndpoint(JdbcTemplate db){this.db=db;}
    @GetMapping(value="/instagram-media/{key}",produces=MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> image(@PathVariable("key") String key){
        if(!key.matches("[a-f0-9]{64}"))return ResponseEntity.notFound().build();
        var rows=db.queryForList("SELECT image_data FROM ig_quiz_story WHERE asset_key=? AND status='PUBLISHING' AND image_data IS NOT NULL",String.class,key);
        if(rows.size()!=1)return ResponseEntity.notFound().build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_JPEG).body(Base64.getDecoder().decode(rows.getFirst()));
    }
}
@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnProperty(name="app.instagram.enabled",havingValue="true")
class InstagramMediaSecurity {
    @Bean @Order(-100)
    SecurityFilterChain instagramMediaChain(HttpSecurity http)throws Exception{
        return http.securityMatcher("/instagram-media/**").authorizeHttpRequests(a->a.requestMatchers(HttpMethod.GET,"/instagram-media/**").permitAll().requestMatchers(HttpMethod.HEAD,"/instagram-media/**").permitAll().anyRequest().denyAll()).build();
    }
}
