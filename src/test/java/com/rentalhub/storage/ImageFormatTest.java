package com.rentalhub.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Recognising a photo by its first bytes, and nothing else. */
class ImageFormatTest {

    @Test
    @DisplayName("each accepted format is recognised by its signature")
    void recognisesSignatures() {
        assertThat(ImageFormat.detect(bytes(0xFF, 0xD8, 0xFF, 0xE0))).contains(ImageFormat.JPEG);
        assertThat(ImageFormat.detect(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0))).contains(ImageFormat.PNG);
        assertThat(ImageFormat.detect("RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1)))
                .contains(ImageFormat.WEBP);
    }

    @Test
    @DisplayName("near misses are refused: a WAV is RIFF too, a GIF and a PDF are not photos we take")
    void refusesLookalikes() {
        assertThat(ImageFormat.detect("RIFF\0\0\0\0WAVEfmt ".getBytes(StandardCharsets.ISO_8859_1))).isEmpty();
        assertThat(ImageFormat.detect("GIF89a".getBytes(StandardCharsets.ISO_8859_1))).isEmpty();
        assertThat(ImageFormat.detect("%PDF-1.4".getBytes(StandardCharsets.ISO_8859_1))).isEmpty();
        assertThat(ImageFormat.detect(bytes(0xFF, 0xD8))).as("too short to be sure").isEmpty();
        assertThat(ImageFormat.detect(new byte[0])).isEmpty();
        assertThat(ImageFormat.detect(null)).isEmpty();
    }

    @Test
    @DisplayName("declared types are read leniently: parameters, case and the unofficial image/jpg")
    void readsDeclaredTypes() {
        assertThat(ImageFormat.fromMediaType("image/jpeg")).contains(ImageFormat.JPEG);
        assertThat(ImageFormat.fromMediaType("image/jpg")).contains(ImageFormat.JPEG);
        assertThat(ImageFormat.fromMediaType("IMAGE/PNG; charset=binary")).contains(ImageFormat.PNG);
        assertThat(ImageFormat.fromMediaType("image/webp")).contains(ImageFormat.WEBP);
        assertThat(ImageFormat.fromMediaType("image/gif")).isEmpty();
        assertThat(ImageFormat.fromMediaType(null)).isEmpty();
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }
}
