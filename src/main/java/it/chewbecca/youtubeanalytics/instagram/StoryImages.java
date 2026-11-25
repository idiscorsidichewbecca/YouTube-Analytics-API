package it.chewbecca.youtubeanalytics.instagram;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Path;

public final class StoryImages {
    private StoryImages() {}
    public static byte[] normalize(Path path) throws IOException {
        try (var input=ImageIO.createImageInputStream(path.toFile())) {
            if (input==null) throw new IllegalArgumentException("Immagine non valida");
            var readers=ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("Immagine JPG/PNG richiesta");
            var reader=readers.next();
            try {
                reader.setInput(input,true,true);
                int w=reader.getWidth(0),h=reader.getHeight(0);
                if (!java.util.Set.of("jpeg","png").contains(reader.getFormatName().toLowerCase(java.util.Locale.ROOT)) || w<320 || h<560 || (long)w*h>24_000_000L || Math.abs(w/(double)h-9.0/16)>0.035)
                    throw new IllegalArgumentException("Serve una grafica verticale 9:16 JPG/PNG, senza ritagli automatici");
                BufferedImage result=new BufferedImage(1080,1920,BufferedImage.TYPE_INT_RGB);
                Graphics2D g=result.createGraphics();
                try {g.setColor(Color.BLACK);g.fillRect(0,0,1080,1920);g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);g.drawImage(reader.read(0),0,0,1080,1920,null);} finally {g.dispose();}
                var out=new ByteArrayOutputStream();
                if(!ImageIO.write(result,"jpeg",out) || out.size()>8*1024*1024) throw new IllegalArgumentException("JPEG non supportato o troppo grande");
                return out.toByteArray();
            } finally { reader.dispose(); }
        }
    }
}
