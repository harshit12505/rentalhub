package com.rentalhub.web.mvc;

import com.rentalhub.domain.model.enums.Currency;
import com.rentalhub.domain.model.enums.PropertyType;
import com.rentalhub.dto.PropertyRequest;
import com.rentalhub.dto.PropertyView;
import com.rentalhub.exception.LocalizedException;
import com.rentalhub.factory.AttributeSpec;
import com.rentalhub.factory.PropertyFactory;
import com.rentalhub.service.PropertyService;
import com.rentalhub.web.DemoSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/**
 * The host's "list a place" form, which exercises the Factory.
 *
 * The type-specific fields are not written into the template: for every type the factory
 * supports, the page gets that creator's own AttributeSpecs — the same declarations the API
 * validates against — and renders one group of inputs per type from them. Choosing a type
 * shows its group; the others are disabled, so the browser does not send them. A new property
 * type therefore appears on this form with no change to the page at all.
 */
@Controller
public class HostListingController {

    private final PropertyService properties;
    private final PropertyFactory factory;
    private final PageNotices notices;

    public HostListingController(PropertyService properties, PropertyFactory factory, PageNotices notices) {
        this.properties = properties;
        this.factory = factory;
        this.notices = notices;
    }

    /**
     * @param type the type to show first; also how the form works with JavaScript switched off:
     *             pick a type and press "show the fields for this type"
     */
    @GetMapping("/host/listings/new")
    public String newListing(@RequestParam(required = false) PropertyType type, Model model) {
        if (model.containsAttribute("listing")) {
            // A refused form, brought back by the redirect as the host typed it, with its errors.
            return form(model);
        }
        PropertyRequest listing = new PropertyRequest();
        listing.setType(type == null ? factory.supportedTypes().iterator().next() : type);
        listing.setCurrency(Currency.INR);
        listing.setMaxGuests(2);
        listing.setBedrooms(1);
        listing.setBathrooms(1);
        model.addAttribute("listing", listing);
        return form(model);
    }

    @PostMapping("/host/listings/new")
    public String create(@Valid @ModelAttribute("listing") PropertyRequest listing,
                         BindingResult errors,
                         HttpServletRequest request,
                         RedirectAttributes redirect) {
        Long userId = DemoSession.userId(request);
        if (userId == null) {
            notices.error(redirect, "error.signInRequired");
            return "redirect:/host/listings/new";
        }
        if (!errors.hasErrors()) {
            try {
                PropertyView created = properties.create(listing, userId);
                notices.success(redirect, "notice.listingCreated");
                return "redirect:/listings/" + created.id();
            } catch (LocalizedException refused) {
                FormErrors.reject(errors, refused);
            }
        }
        FormErrors.keepForNextPage(redirect, "listing", errors);
        return "redirect:/host/listings/new";
    }

    private String form(Model model) {
        model.addAttribute("types", factory.supportedTypes().stream()
                .map(type -> new TypeFields(type, factory.attributeSpecs(type)))
                .toList());
        model.addAttribute("currencies", Currency.values());
        return "host-new-listing";
    }

    /** One property type and the fields only it has, as the form renders them. */
    public record TypeFields(PropertyType type, List<AttributeSpec> fields) {
    }
}
