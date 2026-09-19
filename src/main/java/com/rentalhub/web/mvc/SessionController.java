package com.rentalhub.web.mvc;

import com.rentalhub.service.DemoUserService;
import com.rentalhub.web.DemoSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The navbar's "sign in as" switch: pick a demo user, and the pages act as them.
 *
 * A POST, because it changes state (the session), and then a redirect back to the page it came
 * from. That page is sent along as {@code returnTo} and only honoured if it is a path on this
 * site: accepting any URL there would make the app an "open redirect", a link that looks like
 * it goes to RentalHub but lands somewhere else.
 */
@Controller
public class SessionController {

    private final DemoUserService demoUsers;
    private final PageNotices notices;

    public SessionController(DemoUserService demoUsers, PageNotices notices) {
        this.demoUsers = demoUsers;
        this.notices = notices;
    }

    /** With a userId, act as that user; without one, sign out. */
    @PostMapping("/session/user")
    public String switchUser(@RequestParam(required = false) Long userId,
                             @RequestParam(defaultValue = "/") String returnTo,
                             HttpServletRequest request,
                             RedirectAttributes redirect) {
        if (userId == null) {
            DemoSession.signOut(request);
            notices.info(redirect, "notice.signedOut");
        } else {
            demoUsers.find(userId).ifPresentOrElse(
                    user -> {
                        DemoSession.signIn(request, user.id());
                        notices.success(redirect, "notice.signedIn", user.fullName());
                    },
                    () -> notices.error(redirect, "user.notFound", userId));
        }
        return "redirect:" + onThisSite(returnTo);
    }

    /** A path on this site, or the home page: never "//evil.example" or "https://…". */
    static String onThisSite(String path) {
        boolean local = path != null && path.startsWith("/") && !path.startsWith("//")
                && !path.contains("\\") && !path.contains("\r") && !path.contains("\n");
        return local ? path : "/";
    }
}
