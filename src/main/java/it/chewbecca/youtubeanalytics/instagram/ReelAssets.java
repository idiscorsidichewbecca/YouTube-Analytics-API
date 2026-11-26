package it.chewbecca.youtubeanalytics.instagram;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = {"app.instagram.enabled", "app.instagram.reels-enabled"}, havingValue = "true")
public class ReelAssets {
    static final String VIDEO = "reel.mp4";
    static final String COVER = "cover.jpg";
    private static final long CAPACITY = 280L * 1024 * 1024;
    private static final long VIDEO_LIMIT = 100L * 1024 * 1024;
    private final Path root;
    private final ObjectMapper json;

    public ReelAssets(@Value("${app.instagram.reels-storage}") String storage, ObjectMapper json) {
        this.root = Path.of(storage).toAbsolutePath().normalize();
        this.json = json;
    }

    public static String sourceHash(Path input) throws IOException {
        try (var stream = Files.newInputStream(input)) {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int size;
            while ((size = stream.read(buffer)) != -1) digest.update(buffer, 0, size);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public synchronized void store(String key, Path input, String headline, double coverSecond,
                                   ReelCoverTemplate template) throws IOException {
        if (Files.size(input) > VIDEO_LIMIT) throw new IllegalArgumentException("MP4 oltre il limite di 100 MiB");
        Files.createDirectories(root);
        if (storedBytes() + Files.size(input) + 2_000_000 > CAPACITY
                || Files.getFileStore(root).getUsableSpace() < Files.size(input) + 120_000_000) {
            throw new IllegalStateException("Spazio Reel esaurito; riprova dopo le pubblicazioni in coda");
        }
        Path dir = directory(key);
        Files.createDirectory(dir);
        try {
            Path probe = dir.resolve("probe.json");
            run(List.of("ffprobe", "-v", "error", "-show_entries",
                    "stream=codec_type,codec_name,width,height,pix_fmt,avg_frame_rate,field_order,sample_rate,channels:format=duration",
                    "-of", "json", input.toString()), probe, 20);
            var info = json.readTree(probe.toFile());
            double duration = info.path("format").path("duration").asDouble(-1);
            if (duration < 3 || duration > 900) throw new IllegalArgumentException("Durata Reel valida: da 3 secondi a 15 minuti");
            var streams = info.path("streams");
            var video = streams.findParents("codec_type").stream()
                    .filter(n -> "video".equals(n.path("codec_type").asText())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Nessuna traccia video nel file"));
            int width = video.path("width").asInt();
            int height = video.path("height").asInt();
            if (width <= 0 || height <= 0 || width > 4096 || height > 4096) {
                throw new IllegalArgumentException("Risoluzione del video non supportata");
            }
            var audio = streams.findParents("codec_type").stream()
                    .filter(n -> "audio".equals(n.path("codec_type").asText())).findFirst();
            boolean copyVideo = "h264".equals(video.path("codec_name").asText())
                    && "yuv420p".equals(video.path("pix_fmt").asText())
                    && "progressive".equals(video.path("field_order").asText())
                    && width <= 1080 && height <= 1920
                    && ((double) width / height) >= 0.1
                    && fps(video.path("avg_frame_rate").asText()) >= 23
                    && fps(video.path("avg_frame_rate").asText()) <= 60;
            boolean copyAudio = audio.isEmpty() || audio.filter(n -> "aac".equals(n.path("codec_name").asText())
                    && n.path("channels").asInt() <= 2 && n.path("sample_rate").asInt() <= 48000).isPresent();
            Path output = dir.resolve(VIDEO);
            var command = new java.util.ArrayList<>(List.of("ffmpeg", "-v", "error", "-nostdin", "-threads", "2",
                    "-i", input.toString(), "-map", "0:v:0", "-map", "0:a:0?", "-map_metadata", "-1"));
            command.addAll(copyVideo ? List.of("-c:v", "copy") : List.of("-vf",
                    "scale=1080:1920:force_original_aspect_ratio=decrease:force_divisible_by=2,setsar=1,fps=30",
                    "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p", "-threads", "2",
                    "-crf", "22", "-maxrate", "5M", "-bufsize", "10M", "-g", "60"));
            command.addAll(copyAudio ? List.of("-c:a", "copy") : List.of("-c:a", "aac", "-ar", "48000", "-ac", "2"));
            command.addAll(List.of("-fs", Long.toString(VIDEO_LIMIT + 1),
                    "-use_editlist", "0", "-movflags", "+faststart", output.toString()));
            run(command, null, 105);
            if (Files.size(output) > VIDEO_LIMIT || storedBytes() > CAPACITY) {
                throw new IllegalArgumentException("Video elaborato oltre lo spazio disponibile");
            }
            Path frame = dir.resolve("frame.jpg");
            double at = Math.max(0, Math.min(coverSecond, duration - 0.5));
            run(List.of("ffmpeg", "-v", "error", "-nostdin", "-threads", "2", "-ss",
                    String.format(Locale.ROOT, "%.3f", at), "-i", output.toString(), "-frames:v", "1",
                    "-vf", "scale=1080:1920:force_original_aspect_ratio=increase:force_divisible_by=2,crop=1080:1920",
                    "-threads", "1", "-q:v", "2", frame.toString()), null, 25);
            renderCover(frame, dir.resolve(COVER), headline, template);
            Files.delete(frame);
            Files.delete(probe);
        } catch (IOException | RuntimeException ex) {
            try { remove(key); } catch (IOException cleanup) { ex.addSuppressed(cleanup); }
            throw ex;
        }
    }

    private static double fps(String ratio) {
        var tokens = ratio.split("/");
        if (tokens.length != 2) return 0;
        try { return Double.parseDouble(tokens[0]) / Double.parseDouble(tokens[1]); }
        catch (NumberFormatException e) { return 0; }
    }

    private long storedBytes() throws IOException {
        try (var paths = Files.walk(root, 2)) {
            long total = 0;
            for (Path path : paths.filter(Files::isRegularFile).toList()) total += Files.size(path);
            return total;
        }
    }

    private static void run(List<String> command, Path output, int seconds) throws IOException {
        var builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
        if (output == null) builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        else builder.redirectOutput(output.toFile());
        var process = builder.start();
        try {
            if (process.waitFor(seconds, TimeUnit.SECONDS) && process.exitValue() == 0) return;
            throw new IllegalArgumentException("Il file MP4 non è elaborabile entro i limiti del servizio");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Elaborazione Reel interrotta", ex);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static void renderCover(Path frame, Path output, String headline, ReelCoverTemplate template) throws IOException {
        BufferedImage image = ImageIO.read(frame.toFile());
        if (image == null) throw new IOException("Fotogramma non decodificabile");
        var cover = new BufferedImage(1080, 1920, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = cover.createGraphics();
        try {
            graphics.drawImage(image, 0, 0, 1080, 1920, null);
            template.overlay(graphics, headline);
        } finally {
            graphics.dispose();
        }
        if (!ImageIO.write(cover, "JPEG", output.toFile())) throw new IOException("JPEG non disponibile");
    }

    public FileSystemResource read(String key, String asset) {
        if (!VIDEO.equals(asset) && !COVER.equals(asset)) throw new IllegalArgumentException("File non valido");
        return new FileSystemResource(directory(key).resolve(asset));
    }

    public byte[] preview(String key) throws IOException {
        return Files.readAllBytes(directory(key).resolve(COVER));
    }

    public synchronized void remove(String key) throws IOException {
        Path dir = directory(key);
        if (!Files.isDirectory(dir)) return;
        try (var paths = Files.list(dir)) {
            for (Path path : paths.toList()) Files.deleteIfExists(path);
        }
        Files.deleteIfExists(dir);
    }

    synchronized void cleanOrphans(Instant now, java.util.function.Predicate<String> known) throws IOException {
        if (!Files.isDirectory(root)) return;
        try (var paths = Files.list(root)) {
            for (Path path : paths.filter(Files::isDirectory).toList()) {
                String key = path.getFileName().toString();
                if (key.matches("[a-f0-9]{64}") && Files.getLastModifiedTime(path).toInstant()
                        .isBefore(now.minus(Duration.ofHours(2))) && !known.test(key)) remove(key);
            }
        }
    }

    private Path directory(String key) {
        if (key == null || !key.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Chiave non valida");
        return root.resolve(key);
    }
}
