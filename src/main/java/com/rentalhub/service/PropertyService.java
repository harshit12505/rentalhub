package com.rentalhub.service;

import com.rentalhub.cache.CacheNames;
import com.rentalhub.domain.model.Property;
import com.rentalhub.domain.model.User;
import com.rentalhub.domain.model.enums.UserRole;
import com.rentalhub.domain.repository.BookingRepository;
import com.rentalhub.domain.repository.PropertyRepository;
import com.rentalhub.domain.repository.UserRepository;
import com.rentalhub.dto.PropertyRequest;
import com.rentalhub.dto.PropertyView;
import com.rentalhub.exception.ConflictException;
import com.rentalhub.exception.OperationNotAllowedException;
import com.rentalhub.exception.ResourceNotFoundException;
import com.rentalhub.factory.PropertyFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading and changing listings.
 *
 * Every change to a listing goes through this class, because each one publishes a
 * PropertyChangedEvent, and that event is what keeps the caches honest. A listing
 * changed any other way would leave stale copies behind until they expired.
 */
@Slf4j
@Service
public class PropertyService {

    private final PropertyRepository properties;
    private final UserRepository users;
    private final BookingRepository bookings;
    private final PropertyFactory factory;
    private final ApplicationEventPublisher events;

    public PropertyService(PropertyRepository properties,
                           UserRepository users,
                           BookingRepository bookings,
                           PropertyFactory factory,
                           ApplicationEventPublisher events) {
        this.properties = properties;
        this.users = users;
        this.bookings = bookings;
        this.factory = factory;
        this.events = events;
    }

    /**
     * One listing, for its detail page. Cached in both tiers (see CacheConfig).
     *
     * {@code sync = true}: if many requests miss at the same moment, one loads from
     * the database and the others wait for its answer, instead of all of them hitting
     * Postgres at once (a "cache stampede").
     *
     * Deliberately not @Transactional. The repository call runs in its own short
     * read-only transaction, so a cache hit never opens a transaction or borrows a
     * database connection at all.
     */
    @Cacheable(cacheNames = CacheNames.PROPERTY_BY_ID, key = "#id", sync = true)
    public PropertyView getListing(long id) {
        log.debug("cache.miss cache={} propertyId={} action=load-from-database", CacheNames.PROPERTY_BY_ID, id);
        return properties.findWithDetailsById(id)
                .map(PropertyViews::toView)
                .orElseThrow(() -> new ResourceNotFoundException("property.notFound", id));
    }

    @Transactional
    public PropertyView create(PropertyRequest request, long hostId) {
        User host = users.findById(hostId)
                .orElseThrow(() -> new ResourceNotFoundException("user.notFound", hostId));
        if (host.getRole() != UserRole.HOST) {
            throw new OperationNotAllowedException("property.create.notHost");
        }
        Property property = properties.save(factory.create(request, host));

        events.publishEvent(PropertyChangedEvent.created(property));
        log.info("listing.created propertyId={} type={} hostId={} city=\"{}\"",
                property.getId(), property.getType(), hostId, property.getCity());
        return PropertyViews.toView(property);
    }

    @Transactional
    public PropertyView update(long id, PropertyRequest request, long actingUserId) {
        Property property = loadOwnedBy(id, actingUserId);
        String previousCity = property.getCity();

        factory.update(property, request);
        // Flush now, so the new version number is in the returned view and any
        // database error surfaces here rather than later, at commit.
        properties.flush();

        events.publishEvent(PropertyChangedEvent.updated(property, previousCity));
        log.info("listing.updated propertyId={} version={} hostId={}", id, property.getVersion(), actingUserId);
        return PropertyViews.toView(property);
    }

    @Transactional
    public void delete(long id, long actingUserId) {
        Property property = loadOwnedBy(id, actingUserId);
        // Bookings are history (and, from phase 5, payment records); they must outlive a whim.
        if (bookings.existsByPropertyId(id)) {
            throw new ConflictException("property.delete.hasBookings");
        }
        properties.delete(property);

        events.publishEvent(PropertyChangedEvent.deleted(property));
        log.info("listing.deleted propertyId={} hostId={}", id, actingUserId);
    }

    private Property loadOwnedBy(long id, long actingUserId) {
        Property property = properties.findWithDetailsById(id)
                .orElseThrow(() -> new ResourceNotFoundException("property.notFound", id));
        if (property.getHost().getId() != actingUserId) {
            throw new OperationNotAllowedException("property.notOwner");
        }
        return property;
    }
}
