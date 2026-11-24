package it.chewbecca.youtubeanalytics.publishing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ShortDraftRepository extends JpaRepository<ShortDraft, String> {
    @Query("""
            select draft from ShortDraft draft
            where draft.publishAt >= :from and draft.publishAt < :until and draft.status <> :ignoredStatus
            """)
    List<ShortDraft> findReservations(@Param("from") Instant from, @Param("until") Instant until,
                                     @Param("ignoredStatus") ShortDraft.Status ignoredStatus);
}
