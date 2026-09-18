package com.rentalhub.storage;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * The photo formats a listing accepts, recognised by what the file <em>is</em>, not what it
 * says it is.
 *
 * A browser or client sends a Content-Type header and a file name, and both are just claims:
 * rename {@code invoice.pdf} to {@code photo.jpg} and it arrives as {@code image/jpeg}. The
 * first few bytes of a file (its "magic number", or signature) are much harder to fake by
 * accident, and every image format starts with a fixed one. So the bytes decide, and the
 * stored content type and file extension come from here, never from the upload.
 */
public enum ImageFormat {

    /** JPEG: FF D8 FF. */
    JPEG("image/jpeg", "jpg"),

    /** PNG: 89 'P' 'N' 'G' CR LF SUB LF — designed so that text-mode transfer corrupts it visibly. */
    PNG("image/png", "png"),

    /** WebP: a RIFF container, "RIFF" then a 4-byte size, then "WEBP" at offset 8. */
    WEBP("image/webp", "webp");

    private static final byte[] JPEG_SIGNATURE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] RIFF = {'R', 'I', 'F', 'F'};
    private static final byte[] WEBP_MARK = {'W', 'E', 'B', 'P'};

    private final String mediaType;
    private final String extension;

    ImageFormat(String mediaType, String extension) {
        this.mediaType = mediaType;
        this.extension = extension;
    }

    public String mediaType() {
        return mediaType;
    }

    public String extension() {
        return extension;
    }

    /** The format these bytes really are, or empty if they are none of the accepted ones. */
    public static Optional<ImageFormat> detect(byte[] content) {
        if (startsWith(content, 0, JPEG_SIGNATURE)) {
            return Optional.of(JPEG);
        }
        if (startsWith(content, 0, PNG_SIGNATURE)) {
            return Optional.of(PNG);
        }
        if (startsWith(content, 0, RIFF) && startsWith(content, 8, WEBP_MARK)) {
            return Optional.of(WEBP);
        }
        return Optional.empty();
    }

    /**
     * The format a declared content type names, if it names one of these. {@code image/jpg} is
     * accepted too: it is not a registered type, but plenty of software sends it.
     */
    public static Optional<ImageFormat> fromMediaType(String declared) {
        if (declared == null) {
            return Optional.empty();
        }
        String type = declared.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        if (type.equals("image/jpg")) {
            return Optional.of(JPEG);
        }
        return Arrays.stream(values()).filter(format -> format.mediaType.equals(type)).findFirst();
    }

    private static boolean startsWith(byte[] content, int offset, byte[] expected) {
        if (content == null || content.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (content[offset + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
