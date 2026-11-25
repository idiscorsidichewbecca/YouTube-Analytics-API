package it.chewbecca.youtubeanalytics.thumbnail;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

public final class ThumbnailImages {
    private ThumbnailImages() {}

    public static byte[] normalize(Path image) throws IOException {
        try (var input = ImageIO.createImageInputStream(image.toFile())) {
            if (input == null) throw new IllegalArgumentException("Immagine non leggibile");
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("Carica un file JPG o PNG valido");
            var reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!format.equals("jpeg") && !format.equals("png")) {
                    throw new IllegalArgumentException("Carica un file JPG o PNG valido");
                }
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= height || width < 640 || (long) width * height > 24_000_000) {
                    throw new IllegalArgumentException("Serve un'immagine orizzontale larga almeno 640 pixel");
                }
                return fit(reader.read(0), 1280, 720);
            } finally {
                reader.dispose();
            }
        }
    }

    public static byte[] preview(byte[] jpeg) throws IOException {
        return fit(ImageIO.read(new ByteArrayInputStream(jpeg)), 480, 270);
    }

    private static byte[] fit(BufferedImage original, int width, int height) throws IOException {
        var target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, width, height);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            double scale = Math.min((double) width / original.getWidth(), (double) height / original.getHeight());
            int scaledWidth = (int) Math.round(original.getWidth() * scale);
            int scaledHeight = (int) Math.round(original.getHeight() * scale);
            g.drawImage(original, (width - scaledWidth) / 2, (height - scaledHeight) / 2,
                    scaledWidth, scaledHeight, null);
        } finally {
            g.dispose();
        }
        try (var out = new ByteArrayOutputStream()) {
            ImageIO.write(target, "jpg", out);
            return out.toByteArray();
        }
    }
}
