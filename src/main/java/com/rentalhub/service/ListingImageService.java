package com.rentalhub.service;

import com.rentalhub.dto.PropertyView;
import com.rentalhub.exception.ImageStorageUnavailableException;
import com.rentalhub.exception.InvalidRequestException;
import com.rentalhub.storage.ImageFormat;
import com.rentalhub.storage.ImageStorageSettings;
import com.rentalhub.storage.ImageStore;
import com.rentalhub.storage.ImageStoreException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Adding and removing a listing's photos.
 *
 * <b>The order of the steps is the design</b>, and it is the payment saga from phase 5 in
 * miniature. A file store and a database cannot share a transaction, so:
 * <ol>
 *   <li>check everything that can be checked first — the listing, the host, the limit, the
 *       file itself — so a refusal costs no upload;</li>
 *   <li>upload the file, with no transaction open (it is a network call);</li>
 *   <li>record it in the database, in one short transaction;</li>
 *   <li>if that fails, delete the file again: the compensating action.</li>
 * </ol>
 * Removing goes the other way round (row first, file after the commit), for the same reason:
 * whatever fails, the worst left behind is an unused file, never a listing showing a photo
 * that is not there.
 *
 * <b>The file is checked by its bytes.</b> The declared content type and file name are claims
 * a client makes; the first bytes are what the file is (see ImageFormat). The stored type,
 * and the extension in the key, come from those bytes. A declared type that contradicts them
 * is refused, and so is anything that is not a JPEG, PNG or WebP.
 */
@Slf4j
@Service
public class ListingImageService {

    private final ImageStore store;
    private final ImageStorageSettings settings;
    private final ListingImageUpdates updates;

    ListingImageService(ImageStore store, ImageStorageSettings settings, ListingImageUpdates updates) {
        this.store = store;
        this.settings = settings;
        this.updates = updates;
    }

    public PropertyView.Image upload(long propertyId, long userId, MultipartFile file) {
        if (!store.configured()) {
            throw ImageStorageUnavailableException.notConfigured();
        }
        updates.checkCanAdd(propertyId, userId);

        byte[] content = contentOf(file);
        ImageFormat format = formatOf(content, file.getContentType());
        String key = newKey(propertyId, format);

        try {
            store.put(key, content, format);
        } catch (ImageStoreException failed) {
            log.atWarn().setMessage("image.upload.failed")
                    .addKeyValue("propertyId", propertyId)
                    .addKeyValue("error", failed.getMessage())
                    .setCause(failed.getCause())
                    .log();
            throw ImageStorageUnavailableException.failed();
        }

        try {
            PropertyView.Image image = updates.add(propertyId, userId, key);
            log.atInfo().setMessage("image.uploaded")
                    .addKeyValue("propertyId", propertyId)
                    .addKeyValue("imageId", image.id())
                    .addKeyValue("key", key)
                    .addKeyValue("format", format)
                    .addKeyValue("bytes", content.length)
                    .log();
            return image;
        } catch (RuntimeException refused) {
            // The file is stored but nothing points at it: take it away again.
            deleteQuietly(key);
            throw refused;
        }
    }

    public void delete(long propertyId, long imageId, long userId) {
        Optional<String> key = updates.remove(propertyId, imageId, userId);
        log.atInfo().setMessage("image.removed")
                .addKeyValue("propertyId", propertyId)
                .addKeyValue("imageId", imageId)
                .addKeyValue("key", key.orElse(null))
                .log();
    }

    private byte[] contentOf(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw InvalidRequestException.onField("file", "image.file.empty");
        }
        if (file.getSize() > settings.maxSize().toBytes()) {
            throw InvalidRequestException.onField("file", "image.file.tooLarge", settings.maxSize().toMegabytes());
        }
        try {
            return file.getBytes();
        } catch (IOException unreadable) {
            throw InvalidRequestException.onField("file", "image.file.unreadable");
        }
    }

    /**
     * What the file really is. A declared type is only allowed to agree with the bytes; no
     * declared type at all (or the generic application/octet-stream, which curl sends for a
     * .webp) is fine, because then the bytes are the only evidence anyway.
     */
    private static ImageFormat formatOf(byte[] content, String declaredType) {
        ImageFormat actual = ImageFormat.detect(content)
                .orElseThrow(() -> InvalidRequestException.onField("file", "image.type.unsupported"));
        if (declaredType != null && !declaredType.isBlank()
                && !declaredType.startsWith(MediaType.APPLICATION_OCTET_STREAM_VALUE)
                && ImageFormat.fromMediaType(declaredType).filter(actual::equals).isEmpty()) {
            throw InvalidRequestException.onField("file", "image.type.mismatch", declaredType);
        }
        return actual;
    }

    /**
     * A new key for every upload: {@code listings/<listing>/<random>.<ext>}. Random, so two
     * photos can never overwrite each other and a URL, once handed out, always means the same
     * picture (which is what lets it be cached for a year). The uploaded file name is never
     * used: it is the client's text, and has no business in a storage path.
     */
    private static String newKey(long propertyId, ImageFormat format) {
        return "listings/" + propertyId + "/" + UUID.randomUUID() + "." + format.extension();
    }

    private void deleteQuietly(String key) {
        try {
            store.delete(key);
        } catch (RuntimeException failed) {
            log.atWarn().setMessage("image.orphaned")
                    .addKeyValue("key", key)
                    .addKeyValue("error", failed.getMessage())
                    .log();
        }
    }
}
