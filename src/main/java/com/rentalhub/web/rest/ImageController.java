package com.rentalhub.web.rest;

import com.rentalhub.exception.ImageStorageUnavailableException;
import com.rentalhub.exception.ResourceNotFoundException;
import com.rentalhub.storage.ImageStore;
import com.rentalhub.storage.ImageStoreException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Serves uploaded listing photos from storage.
 *
 * <b>Why photos go through the application</b> rather than straight from the bucket: the
 * bucket stays private (AWS blocks public buckets by default, and nothing has to be opened up),
 * any S3-compatible store works the same way, and a photo's URL never expires — which matters,
 * because listings are cached with their photo URLs in them, and a pre-signed link would go
 * stale inside the cache. The cost is that the image bytes pass through this server; a CDN in
 * front of this path is the usual next step when that starts to matter.
 *
 * The path only matches keys this application creates ({@code listings/<id>/<random>.<ext>}),
 * so it cannot be used to read anything else that happens to be in the bucket.
 */
@Slf4j
@Tag(name = "Photos", description = "A listing's photos: uploaded by its host to S3 (or any S3-compatible store) and served back by this app.")
@RestController
public class ImageController {

    /** Keys are never reused, so a photo's bytes never change and may be cached for a year. */
    private static final CacheControl FOREVER = CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable();

    private final ImageStore store;

    public ImageController(ImageStore store) {
        this.store = store;
    }

    @Operation(summary = "A photo",
            description = "The bytes of an uploaded photo, with its type, cacheable for a year (a URL never changes what it points at). Only keys this app creates are served.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "The photo (image/jpeg, image/png or image/webp)"),
                    @ApiResponse(responseCode = "404", description = "There is no such photo", content = @Content(mediaType = "application/problem+json", examples = @ExampleObject(ApiExamples.NOT_FOUND))),
                    @ApiResponse(responseCode = "503", description = "No image storage is configured", content = @Content(mediaType = "application/problem+json", examples = @ExampleObject(ApiExamples.NO_IMAGE_STORAGE)))})
    @GetMapping("/images/listings/{propertyId:\\d+}/{fileName:[0-9a-f-]+\\.(?:jpg|png|webp)}")
    public ResponseEntity<InputStreamResource> photo(@PathVariable long propertyId, @PathVariable String fileName) {
        if (!store.configured()) {
            throw ImageStorageUnavailableException.notConfigured();
        }
        String key = "listings/" + propertyId + "/" + fileName;
        try {
            ImageStore.StoredImage image = store.get(key)
                    .orElseThrow(() -> new ResourceNotFoundException("image.file.notFound"));
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(image.contentType()))
                    .contentLength(image.length())
                    .cacheControl(FOREVER)
                    // The stream is closed by Spring once it has been written out.
                    .body(new InputStreamResource(image.content()));
        } catch (ImageStoreException failed) {
            log.atWarn().setMessage("image.read.failed")
                    .addKeyValue("key", key)
                    .addKeyValue("error", failed.getMessage())
                    .log();
            throw ImageStorageUnavailableException.failed();
        }
    }
}
