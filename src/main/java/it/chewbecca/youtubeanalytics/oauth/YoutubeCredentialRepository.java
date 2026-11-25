package it.chewbecca.youtubeanalytics.oauth;

import org.springframework.data.jpa.repository.JpaRepository;

public interface YoutubeCredentialRepository extends JpaRepository<YoutubeCredential, Long> {
}
