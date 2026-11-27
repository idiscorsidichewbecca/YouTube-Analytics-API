package it.chewbecca.youtubeanalytics.thumbnail;

import java.util.ArrayList;
import java.util.List;
import org.springframework.web.util.HtmlUtils;

/** Keeps representative spoken lines, not the original WebVTT track. */
final class CaptionExcerpt {
    static final int MAX_LINES = 150;
    static final int MAX_CHARS = 15_000;
    record Result(String text, boolean truncated) {}

    static Result fromVtt(String vtt) {
        List<String> lines = new ArrayList<>();
        String previous = "";
        for (String block : vtt.replace("\r\n", "\n").replace('\r', '\n').replace("\uFEFF", "").split("\n\\s*\n")) {
            String[] raw = block.strip().split("\n");
            if (raw.length == 0 || raw[0].matches("^(WEBVTT|NOTE|STYLE|REGION)(\\s.*)?$")) continue;
            int start = 0;
            for (int i = 0; i < raw.length; i++) {
                if (raw[i].contains("-->")) { start = i + 1; break; }
            }
            for (int i = start; i < raw.length; i++) {
                String line = HtmlUtils.htmlUnescape(raw[i].replaceAll("<[^>]*>", "")).strip().replaceAll("\\s+", " ");
                if (line.isBlank() || line.equals(previous) || line.contains("-->")) continue;
                lines.add(line);
                previous = line;
            }
        }
        if (lines.isEmpty()) return new Result("", false);
        var selected = new ArrayList<String>();
        if (lines.size() <= MAX_LINES) {
            selected.addAll(lines);
        } else {
            selected.addAll(lines.subList(0, 50));
            int middle = (lines.size() - 50) / 2;
            selected.addAll(lines.subList(middle, middle + 50));
            selected.addAll(lines.subList(lines.size() - 50, lines.size()));
        }
        // Distribute the character budget across the selected lines, so very long
        // opening cues cannot crowd out the middle and ending of the video.
        StringBuilder result = new StringBuilder();
        int perLine = (MAX_CHARS - selected.size() + 1) / selected.size();
        boolean clipped = lines.size() > MAX_LINES;
        for (String line : selected) {
            if (!result.isEmpty()) result.append('\n');
            if (line.length() > perLine) {
                int end = perLine;
                if (Character.isHighSurrogate(line.charAt(end - 1))) end--;
                result.append(line, 0, end);
                clipped = true;
            } else result.append(line);
        }
        return new Result(result.toString(), clipped);
    }

    private CaptionExcerpt() {}
}
