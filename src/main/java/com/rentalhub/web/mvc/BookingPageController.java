package com.rentalhub.web.mvc;

import com.rentalhub.domain.model.enums.Currency;
import com.rentalhub.domain.model.enums.PaymentStatus;
import com.rentalhub.dto.BookingView;
import com.rentalhub.exception.LocalizedException;
import com.rentalhub.service.BookingService;
import com.rentalhub.service.CurrencyService;
import com.rentalhub.web.DemoSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** The signed-in guest's trips, each with a cancel button. */
@Controller
public class BookingPageController {

    private final BookingService bookings;
    private final CurrencyService currencies;
    private final PageNotices notices;

    public BookingPageController(BookingService bookings, CurrencyService currencies, PageNotices notices) {
        this.bookings = bookings;
        this.currencies = currencies;
        this.notices = notices;
    }

    @GetMapping("/bookings")
    public String myBookings(@RequestParam(required = false) Currency currency,
                             HttpServletRequest request,
                             Model model) {
        Long userId = DemoSession.userId(request);
        if (userId != null) {
            model.addAttribute("bookings", currencies.inCurrency(bookings.forGuest(userId), currency));
        }
        model.addAttribute("currencies", Currency.values());
        model.addAttribute("currency", currency);
        return "my-bookings";
    }

    /** Cancelling a paid booking refunds it in full; the notice says which happened. */
    @PostMapping("/bookings/{id}/cancel")
    public String cancel(@PathVariable long id, HttpServletRequest request, RedirectAttributes redirect) {
        Long userId = DemoSession.userId(request);
        if (userId == null) {
            notices.error(redirect, "error.signInRequired");
            return "redirect:/bookings";
        }
        try {
            BookingView cancelled = bookings.cancel(id, userId);
            if (cancelled.payment().status() == PaymentStatus.REFUNDED) {
                notices.success(redirect, "notice.cancelledRefunded");
            } else {
                notices.success(redirect, "notice.cancelled");
            }
        } catch (LocalizedException refused) {
            notices.error(redirect, refused);
        }
        return "redirect:/bookings";
    }
}
