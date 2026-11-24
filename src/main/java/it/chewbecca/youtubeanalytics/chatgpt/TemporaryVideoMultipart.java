package it.chewbecca.youtubeanalytics.chatgpt;

import org.springframework.web.multipart.MultipartFile;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Adatta un download temporaneo a un MultipartFile senza caricarlo tutto in RAM. */
public final class TemporaryVideoMultipart implements MultipartFile {
    private final Path source;
    private final String fileName;
    public TemporaryVideoMultipart(Path source, String fileName) {
        this.source = source;
        this.fileName = fileName;
    }
    @Override public String getName() { return "video"; }
    @Override public String getOriginalFilename() { return fileName; }
    @Override public String getContentType() { return "video/mp4"; }
    @Override public boolean isEmpty() { return getSize() == 0; }
    @Override public long getSize() {
        try { return Files.size(source); }
        catch (IOException e) { throw new IllegalStateException("Video temporaneo non leggibile", e); }
    }
    @Override public byte[] getBytes() throws IOException { return Files.readAllBytes(source); }
    @Override public InputStream getInputStream() throws IOException { return Files.newInputStream(source); }
    @Override public void transferTo(File dest) throws IOException {
        Files.copy(source, dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
    @Override public void transferTo(Path dest) throws IOException {
        Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
    }
}
