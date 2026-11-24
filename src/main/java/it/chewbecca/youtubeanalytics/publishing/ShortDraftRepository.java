package it.chewbecca.youtubeanalytics.publishing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface ShortDraftRepository extends JpaRepository<ShortDraft, String> {
    List<ShortDraft> findByPublishAtBetweenAndStatusNot(Instant from, Instant to, ShortDraft.Status status);
}
