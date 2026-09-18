package com.rentalhub.web.rest;

import com.rentalhub.dto.PropertyView;
import com.rentalhub.service.ListingImageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

/**
 * A listing's photos, added and removed by its host.
 *
 * An upload is a {@code multipart/form-data} request with the photo in a part called
 * {@code file}, which is what an HTML form with {@code <input type="file" name="file">} sends,
 * and what {@code curl.exe -F "file=@photo.jpg"} sends.
 */
@Tag(name = "Photos", description = "A listing's photos: uploaded by its host to S3 (or any S3-compatible store) and served back by this app.")
@RestController
@RequestMapping("/api/properties/{propertyId}/images")
public class ListingImageController {

    private final ListingImageService images;

    public ListingImageController(ListingImageService images) {
        this.images = images;
    }

    /** 201, with the photo's URL in Location. 503 when no image storage is configured. */
    @Operation(summary = "Upload a photo",
            description = "By the listing's host, as multipart/form-data with the photo in a part called file. JPEG, PNG or WebP, at most 5 MB, at most 10 per listing. The type is decided by the file's first bytes, not its name or declared type. 503 when no image storage is configured.",
            responses = {
                    @ApiResponse(responseCode = "201", description = "Stored; the Location header is the photo's URL", content = @Content(mediaType = "application/json", examples = @ExampleObject(ApiExamples.IMAGE))),
                    @ApiResponse(responseCode = "400", description = "Not a JPEG, PNG or WebP photo, empty, or too large", content = @Content(mediaType = "application/problem+json", examples = @ExampleObject(ApiExamples.NOT_A_PHOTO))),
                    @ApiResponse(responseCode = "403", description = "Not the host of this listing", content = @Content(mediaType = "application/problem+json", examples = @ExampleObject(ApiExamples.NOT_ALLOWED))),
                    @ApiResponse(responseCode = "503", description = "No image storage is configured", content = @Content(mediaType = "application/problem+json", examples = @ExampleObject(ApiExamples.NO_IMAGE_STORAGE)))})
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PropertyView.Image> upload(@PathVariable long propertyId,
                                                     @RequestHeader(ApiHeaders.DEMO_USER_ID) long userId,
                                                     @RequestPart("file") MultipartFile file) {
        PropertyView.Image image = images.upload(propertyId, userId, file);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath().path(image.url()).build().toUri();
        return ResponseEntity.created(location).body(image);
    }

    @Operation(summary = "Remove a photo",
            description = "By the listing's host. The row goes first; the file is deleted from storage after the commit.",
            responses = {
                    @ApiResponse(responseCode = "204", description = "Removed"),
                    @ApiResponse(responseCode = "404", description = "There is no such photo on this listing", content = @Content(mediaType = "application/problem+json", examples = @ExampleObject(ApiExamples.NOT_FOUND)))})
    @DeleteMapping("/{imageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long propertyId,
                       @PathVariable long imageId,
                       @RequestHeader(ApiHeaders.DEMO_USER_ID) long userId) {
        images.delete(propertyId, imageId, userId);
    }
}
