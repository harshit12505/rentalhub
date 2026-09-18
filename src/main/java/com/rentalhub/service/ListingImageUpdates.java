package com.rentalhub.service;

import com.rentalhub.domain.model.Property;
import com.rentalhub.domain.model.PropertyImage;
import com.rentalhub.domain.repository.PropertyImageRepository;
import com.rentalhub.domain.repository.PropertyRepository;
import com.rentalhub.dto.PropertyView;
import com.rentalhub.exception.ConflictException;
import com.rentalhub.exception.OperationNotAllowedException;
import com.rentalhub.exception.ResourceNotFoundException;
import com.rentalhub.storage.ImageStorageSettings;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * The database half of adding and removing a listing photo, one short transaction per step.
 *
 * A bean of its own, like BookingUpdates, so that ListingImageService can call these through
 * the transactional proxy and do the uploading in between, outside any transaction.
 */
@Service
public class ListingImageUpdates {

    /** Where uploaded photos are served from; see web/rest/ImageController. */
    public static final String URL_PREFIX = "/images/";

    private final PropertyRepository properties;
    private final PropertyImageRepository images;
    private final ApplicationEventPublisher events;
    private final ImageStorageSettings settings;

    ListingImageUpdates(PropertyRepository properties,
                        PropertyImageRepository images,
                        ApplicationEventPublisher events,
                        ImageStorageSettings settings) {
        this.properties = properties;
        this.images = images;
        this.events = events;
        this.settings = settings;
    }

    /**
     * May this user add a photo to this listing? Asked before anything is uploaded, so a
     * refusal costs no storage call. The same checks run again when the row is written,
     * because a lot can happen during an upload.
     */
    @Transactional(readOnly = true)
    public void checkCanAdd(long propertyId, long userId) {
        Property property = properties.findWithDetailsById(propertyId)
                .orElseThrow(() -> new ResourceNotFoundException("property.notFound", propertyId));
        requireHost(property, userId);
        requireRoom(property);
    }

    /**
     * Records an uploaded photo, last in the listing's order.
     *
     * The listing is loaded with a forced version bump (see
     * PropertyRepository.findForImageChangeById), so two uploads at the same moment cannot both
     * pass the limit or take the same position: the second to commit fails, and its file is
     * deleted by the caller.
     */
    @Transactional
    public PropertyView.Image add(long propertyId, long userId, String key) {
        Property property = properties.findForImageChangeById(propertyId)
                .orElseThrow(() -> new ResourceNotFoundException("property.notFound", propertyId));
        requireHost(property, userId);
        requireRoom(property);

        int position = property.getImages().stream().mapToInt(PropertyImage::getSortOrder).max().orElse(-1) + 1;
        PropertyImage image = new PropertyImage(URL_PREFIX + key, key, position);
        property.addImage(image);
        images.saveAndFlush(image);

        // The listing's cached view and its city's search pages (the cover photo) are stale now.
        events.publishEvent(PropertyChangedEvent.updated(property, property.getCity()));
        return new PropertyView.Image(image.getId(), image.getUrl(), image.getSortOrder());
    }

    /**
     * Removes a photo's row. Its file is deleted after the commit (ImageObjectCleaner).
     *
     * @return the storage key of the removed photo, or empty for a photo that was never
     *         uploaded here (one with an outside URL)
     */
    @Transactional
    public Optional<String> remove(long propertyId, long imageId, long userId) {
        Property property = properties.findForImageChangeById(propertyId)
                .orElseThrow(() -> new ResourceNotFoundException("property.notFound", propertyId));
        requireHost(property, userId);
        PropertyImage image = property.getImages().stream()
                .filter(candidate -> candidate.getId() == imageId)
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("image.notFound", imageId));

        property.removeImage(image);
        events.publishEvent(PropertyChangedEvent.updated(property, property.getCity()));
        Optional<String> key = Optional.ofNullable(image.getS3Key());
        key.ifPresent(stored -> events.publishEvent(new ListingImagesRemovedEvent(List.of(stored))));
        return key;
    }

    private static void requireHost(Property property, long userId) {
        if (property.getHost().getId() != userId) {
            throw new OperationNotAllowedException("property.notOwner");
        }
    }

    private void requireRoom(Property property) {
        if (property.getImages().size() >= settings.maxPerListing()) {
            throw new ConflictException("image.limit", settings.maxPerListing());
        }
    }
}
