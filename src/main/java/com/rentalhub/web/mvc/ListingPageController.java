package com.rentalhub.web.mvc;

import com.rentalhub.domain.model.enums.BookingStatus;
import com.rentalhub.domain.model.enums.Currency;
import com.rentalhub.dto.BookingRequest;
import com.rentalhub.dto.BookingView;
import com.rentalhub.dto.PropertyView;
import com.rentalhub.dto.ReviewRequest;
import com.rentalhub.exception.LocalizedException;
import com.rentalhub.exception.ResourceNotFoundException;
import com.rentalhub.service.BookingService;
import com.rentalhub.service.CurrencyService;
import com.rentalhub.service.FavoriteService;
import com.rentalhub.service.ListingImageService;
import com.rentalhub.service.PropertyService;
import com.rentalhub.service.ReviewService;
import com.rentalhub.web.DemoSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A listing's page, and every form on it: book, review, save, and (for its host) add or
 * remove photos.
 *
 * Each form posts here and then redirects back: with a one-line notice when it worked (see
 * PageNotices), or, when a booking or a review is refused, with the form as the guest typed
 * it and each problem next to its field (see FormErrors). All the rules are the services' —
 * the same ones the REST API applies.
 */
@Controller
public class ListingPageController {

    /** Stripe's test payment methods, offered instead of a card-number box (see the teaching doc). */
    static final List<String> TEST_CARDS = List.of("pm_card_visa", "pm_card_visa_chargeDeclined",
            "pm_card_authenticationRequired");

    private final PropertyService properties;
    private final CurrencyService currencies;
    private final ReviewService reviews;
    private final BookingService bookings;
    private final FavoriteService favorites;
    private final ListingImageService images;
    private final MessageSource messages;
    private final PageNotices notices;
    private final MoneyFormat money;
    private final SpringValidatorAdapter validator;
    private final Clock clock;

    public ListingPageController(PropertyService properties,
                                 CurrencyService currencies,
                                 ReviewService reviews,
                                 BookingService bookings,
                                 FavoriteService favorites,
                                 ListingImageService images,
                                 MessageSource messages,
                                 PageNotices notices,
                                 MoneyFormat money,
                                 Validator validator,
                                 Clock clock) {
        this.properties = properties;
        this.currencies = currencies;
        this.reviews = reviews;
        this.bookings = bookings;
        this.favorites = favorites;
        this.images = images;
        this.messages = messages;
        this.notices = notices;
        this.money = money;
        this.validator = new SpringValidatorAdapter(validator);
        this.clock = clock;
    }

    @GetMapping("/listings/{id}")
    public String detail(@PathVariable long id,
                         @RequestParam(required = false) Currency currency,
                         HttpServletRequest request,
                         Model model) {
        // After a refused form, the redirect brought it back as typed, with its errors.
        if (!model.containsAttribute("booking")) {
            model.addAttribute("booking", newBooking());
        }
        if (!model.containsAttribute("review")) {
            model.addAttribute("review", newReview());
        }
        return showListing(id, currency, request, model);
    }

    /**
     * Validated here rather than with @Valid: the listing is the one in the address, not a form
     * field, and the booking request (the API's, which carries it in its body) requires it — so it
     * is set first, then checked.
     */
    @PostMapping("/listings/{id}/book")
    public String book(@PathVariable long id,
                       @ModelAttribute("booking") BookingRequest booking,
                       BindingResult errors,
                       HttpServletRequest request,
                       RedirectAttributes redirect) {
        Long userId = DemoSession.userId(request);
        if (userId == null) {
            notices.error(redirect, "error.signInRequired");
            return "redirect:/listings/" + id;
        }
        booking.setPropertyId(id);
        validator.validate(booking, errors);
        if (!errors.hasErrors()) {
            try {
                BookingView booked = bookings.book(booking, userId);
                if (booked.status() == BookingStatus.PENDING) {
                    notices.info(redirect, "notice.bookingPending");
                } else {
                    notices.success(redirect, "notice.booked", booked.nights(),
                            money.format(booked.totalAmount(), booked.currency()));
                }
                return "redirect:/bookings";
            } catch (ResourceNotFoundException missing) {
                throw missing;
            } catch (LocalizedException refused) {
                FormErrors.reject(errors, refused);
            }
        }
        FormErrors.keepForNextPage(redirect, "booking", errors);
        return "redirect:/listings/" + id + "#book";
    }

    @PostMapping("/listings/{id}/reviews")
    public String review(@PathVariable long id,
                         @Valid @ModelAttribute("review") ReviewRequest review,
                         BindingResult errors,
                         HttpServletRequest request,
                         RedirectAttributes redirect) {
        Long userId = DemoSession.userId(request);
        if (userId == null) {
            notices.error(redirect, "error.signInRequired");
            return "redirect:/listings/" + id;
        }
        if (!errors.hasErrors()) {
            try {
                reviews.create(id, review, userId);
                notices.success(redirect, "notice.reviewed");
                return "redirect:/listings/" + id + "#reviews";
            } catch (ResourceNotFoundException missing) {
                throw missing;
            } catch (LocalizedException refused) {
                FormErrors.reject(errors, refused);
            }
        }
        FormErrors.keepForNextPage(redirect, "review", errors);
        return "redirect:/listings/" + id + "#reviews";
    }

    /** Save or unsave: the button says which, and pressing it again undoes it. */
    @PostMapping("/listings/{id}/favorite")
    public String toggleFavorite(@PathVariable long id, HttpServletRequest request, RedirectAttributes redirect) {
        Long userId = DemoSession.userId(request);
        if (userId == null) {
            notices.error(redirect, "error.signInRequired");
        } else if (favorites.isSaved(id, userId)) {
            favorites.remove(id, userId);
            notices.info(redirect, "notice.unfavourited");
        } else {
            favorites.save(id, userId);
            notices.success(redirect, "notice.favourited");
        }
        return "redirect:/listings/" + id;
    }

    @PostMapping("/listings/{id}/photos")
    public String addPhoto(@PathVariable long id,
                           @RequestParam("file") MultipartFile file,
                           HttpServletRequest request,
                           RedirectAttributes redirect) {
        Long userId = DemoSession.userId(request);
        if (userId == null) {
            notices.error(redirect, "error.signInRequired");
            return "redirect:/listings/" + id;
        }
        try {
            images.upload(id, userId, file);
            notices.success(redirect, "notice.photoAdded");
        } catch (LocalizedException refused) {
            notices.error(redirect, refused);
        }
        return "redirect:/listings/" + id + "#photos";
    }

    /** A POST to an action, because an HTML form cannot send DELETE. */
    @PostMapping("/listings/{id}/photos/{imageId}/delete")
    public String removePhoto(@PathVariable long id,
                              @PathVariable long imageId,
                              HttpServletRequest request,
                              RedirectAttributes redirect) {
        Long userId = DemoSession.userId(request);
        if (userId == null) {
            notices.error(redirect, "error.signInRequired");
            return "redirect:/listings/" + id;
        }
        try {
            images.delete(id, imageId, userId);
            notices.success(redirect, "notice.photoRemoved");
        } catch (LocalizedException refused) {
            notices.error(redirect, refused);
        }
        return "redirect:/listings/" + id + "#photos";
    }

    /** The booking form as it first appears: two nights a week from today, one guest, the card that succeeds. */
    private BookingRequest newBooking() {
        BookingRequest booking = new BookingRequest();
        booking.setCheckIn(LocalDate.now(clock).plusDays(7));
        booking.setCheckOut(LocalDate.now(clock).plusDays(9));
        booking.setGuests(1);
        booking.setPaymentMethodId(TEST_CARDS.getFirst());
        return booking;
    }

    private static ReviewRequest newReview() {
        ReviewRequest review = new ReviewRequest();
        review.setRating(5);
        return review;
    }

    /** Everything the page shows besides the two forms, which the caller has already put in the model. */
    private String showListing(long id, Currency currency, HttpServletRequest request, Model model) {
        PropertyView listing = currencies.inCurrency(properties.getListing(id), currency);
        Long userId = DemoSession.userId(request);
        boolean isHost = userId != null && userId == listing.host().id();

        model.addAttribute("listing", listing);
        model.addAttribute("facts", facts(listing, LocaleContextHolder.getLocale()));
        model.addAttribute("reviews", reviews.forListing(id));
        model.addAttribute("rating", reviews.ratingsFor(List.of(id)).get(id));
        model.addAttribute("isHost", isHost);
        model.addAttribute("canBook", userId != null && !isHost && listing.active());
        model.addAttribute("saved", userId != null && favorites.isSaved(id, userId));
        model.addAttribute("testCards", TEST_CARDS);
        model.addAttribute("currencies", Currency.values());
        model.addAttribute("currency", currency);
        return "property-detail";
    }

    /**
     * The listing's own fields — whatever its type — as label and value, in the page's
     * language. Generic on purpose: the names come from the listing, the labels from the same
     * message keys the validation errors use, so no page ever needs to know a property type.
     */
    private List<Fact> facts(PropertyView listing, Locale locale) {
        List<Fact> facts = new ArrayList<>();
        listing.attributes().forEach((name, value) ->
                facts.add(new Fact(messages.getMessage("property.attribute." + name, null, name, locale),
                        valueText(name, value, locale))));
        return facts;
    }

    private String valueText(String name, Object value, Locale locale) {
        if (value == null) {
            return messages.getMessage("detail.notStated", null, locale);
        }
        if (value instanceof Boolean flag) {
            return messages.getMessage("attribute.value." + flag, null, locale);
        }
        if (value instanceof BigDecimal number) {
            return NumberFormat.getNumberInstance(locale).format(number);
        }
        if (value instanceof Number number) {
            return NumberFormat.getIntegerInstance(locale).format(number);
        }
        return messages.getMessage("property.attribute." + name + "." + value, null, value.toString(), locale);
    }

    /** One line of the listing's details. */
    public record Fact(String label, String value) {
    }
}
