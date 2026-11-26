package it.chewbecca.youtubeanalytics.instagram;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InstagramReelScheduleTest {
    @TempDir Path staging;
    private JdbcTemplate db;
    private final InstagramGraphClient graph = mock(InstagramGraphClient.class);
    private final ReelAssets assets = mock(ReelAssets.class);
    private final Clock clock = mock(Clock.class);
    private final OffsetDateTime now = OffsetDateTime.parse("2026-11-30T10:00:00Z");

    @BeforeEach
    void setUp() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V13__instagram_reels.sql")).execute(source);
        db = new JdbcTemplate(source);
        when(graph.account()).thenReturn("123");
        when(graph.requirePublishingConnection()).thenReturn(new InstagramGraphClient.Connection("123", "channel", true, true));
        when(clock.instant()).thenReturn(now.toInstant());
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(assets.preview(anyString())).thenReturn(new byte[] {1, 2, 3});
        when(assets.read(anyString(), anyString())).thenReturn(new org.springframework.core.io.FileSystemResource(staging.resolve("absent")));
    }

    @Test
    void schedulesInRomeKeepsOneDayPerReelAndDeduplicatesSameVideo() throws Exception {
        var schedule = new InstagramReelSchedule(db, graph, assets, clock, true);
        Path first = Files.writeString(staging.resolve("first.mp4"), "video first");
        Path second = Files.writeString(staging.resolve("second.mp4"), "video second");
        var a = schedule.schedule(first, "Titolo A", "Descrizione", "TESTO A", 2).receipt();
        var b = schedule.schedule(second, "Titolo B", "Descrizione", "TESTO B", 2).receipt();
        var retry = schedule.schedule(first, "Altro titolo", "Descrizione", "TESTO C", 2).receipt();
        assertEquals("SCHEDULED", a.reel().state());
        assertEquals(ZoneOffset.ofHours(1), a.reel().publishAt().getOffset());
        assertEquals(a.reel().id(), retry.reel().id());
        assertEquals("Titolo A\n\nDescrizione", retry.reel().caption());
        assertNotEquals(a.reel().publishAt().toLocalDate(), b.reel().publishAt().toLocalDate());
        assertEquals(2, schedule.status(null).size());
        verify(assets, times(2)).store(anyString(), any(), anyString(), anyDouble());
        verify(graph, never()).publish(anyString(), anyString());
    }

    @Test
    void usesMetaCustomCoverAndNeverRetriesAnUncertainPublish() throws Exception {
        var schedule = new InstagramReelSchedule(db, graph, assets, clock, true);
        var reel = schedule.schedule(Files.writeString(staging.resolve("first.mp4"), "video"),
                "Titolo", "Descrizione", "TESTO", 2).receipt().reel();
        var worker = new InstagramReelWorker(db, graph, assets, clock, "https://example.com", true);
        when(clock.instant()).thenReturn(reel.publishAt().minusMinutes(5).toInstant());
        when(graph.createReel(eq("123"), endsWith("/reel.mp4"), endsWith("/cover.jpg"), eq(reel.caption())))
                .thenReturn("456");
        when(graph.containerStatus("123", "456")).thenReturn("FINISHED");
        worker.advance();
        assertEquals("PROCESSING", schedule.status(reel.id()).getFirst().state());
        when(clock.instant()).thenReturn(reel.publishAt().minusMinutes(2).toInstant());
        worker.advance();
        verify(graph, never()).publish(anyString(), anyString());

        when(clock.instant()).thenReturn(reel.publishAt().toInstant());
        when(graph.publish("123", "456")).thenThrow(new IllegalStateException("timeout"));
        worker.advance();
        assertEquals("UNKNOWN", schedule.status(reel.id()).getFirst().state());

        when(clock.instant()).thenReturn(reel.publishAt().plusMinutes(2).toInstant());
        when(graph.containerStatus("123", "456")).thenReturn("PUBLISHED");
        worker.advance();
        assertEquals("PUBLISHED", schedule.status(reel.id()).getFirst().state());
        verify(graph, times(1)).publish("123", "456");
    }

    @Test
    void skipsOccupiedDatesAndUsesActualInstagramCohorts() throws Exception {
        var json = new ObjectMapper();
        ZonedDateTime today = ZonedDateTime.parse("2026-11-30T11:00:00+01:00[Europe/Rome]");
        var data = json.readTree("""
                [
                    {"timestamp":"2026-11-05T12:00:00+0000","media_product_type":"REELS","like_count":2,"comments_count":1},
                    {"timestamp":"2026-11-06T12:00:00+00:00","media_product_type":"REELS","like_count":2,"comments_count":1},
                    {"timestamp":"2026-11-07T12:00:00Z","media_product_type":"REELS","like_count":2},
                    {"timestamp":"2026-11-07T12:00:00Z","media_product_type":"FEED","like_count":2,"comments_count":1}
                ]
                """);
        var observations = ReelSlotPlanner.observations(List.of(data.get(0), data.get(1), data.get(2), data.get(3)), today);
        assertEquals(2, observations.size());
        assertEquals(13, observations.getFirst().at().getHour());
        var occupied = Set.of(LocalDate.of(2026, 12, 2));
        var ranked = ReelSlotPlanner.rank(today, occupied, observations);
        assertEquals(OffsetDateTime.parse("2026-12-09T18:00:00+01:00"), ranked.getFirst().at());
        assertTrue(ranked.stream().noneMatch(slot -> occupied.contains(slot.at().toLocalDate())));
        assertTrue(ranked.stream().allMatch(slot -> slot.at().toInstant().isBefore(today.plusDays(21).toInstant())));
    }
}
