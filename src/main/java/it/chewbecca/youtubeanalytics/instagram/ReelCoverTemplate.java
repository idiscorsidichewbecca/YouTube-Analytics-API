package it.chewbecca.youtubeanalytics.instagram;

import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Locale;

/** Reusable overlays for vertical Reels. The subject and scenery always come from the source frame. */
public enum ReelCoverTemplate {
    PERSONAGGIO(new Color(15, 9, 13), new Color(216, 163, 117), 204),
    LUOGO(new Color(5, 13, 21), new Color(167, 199, 215), 188),
    EVENTO(new Color(20, 13, 6), new Color(225, 190, 112), 198);

    private final Color tint;
    private final Color accent;
    private final int opacity;

    ReelCoverTemplate(Color tint, Color accent, int opacity) {
        this.tint = tint;
        this.accent = accent;
        this.opacity = opacity;
    }

    public static ReelCoverTemplate fromInput(String input) {
        if (input == null || input.isBlank()) return PERSONAGGIO;
        return valueOf(input.strip().toUpperCase(Locale.ROOT));
    }

    void overlay(Graphics2D graphics, String headline) {
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setPaint(new GradientPaint(0, 965,
                new Color(tint.getRed(), tint.getGreen(), tint.getBlue(), 0), 0, 1420,
                new Color(tint.getRed(), tint.getGreen(), tint.getBlue(), opacity)));
        graphics.fillRect(0, 965, 1080, 955);

        int left = 130;
        int baseline = 1380;
        int maxWidth = 820;
        var font = new Font("DejaVu Sans", Font.BOLD, 56);
        graphics.setFont(font);
        int measured = graphics.getFontMetrics().stringWidth(headline);
        if (measured > maxWidth) graphics.setFont(font.deriveFont(56f * maxWidth / measured));

        graphics.setColor(accent);
        graphics.fillRoundRect(left, baseline - 78, 54, 4, 4, 4);
        graphics.setColor(new Color(0, 0, 0, 160));
        graphics.drawString(headline, left + 1, baseline + 2);
        graphics.setColor(new Color(247, 245, 240));
        graphics.drawString(headline, left, baseline);
    }
}
