package com.rentalhub.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * Who the web pages are acting as: the user picked in the navbar's "sign in as" list, kept in
 * the HTTP session.
 *
 * The REST and GraphQL APIs name the user in the X-Demo-User-Id header instead; the session is
 * only for a browser, which cannot be asked to send a header. Neither is a login, by design.
 */
public final class DemoSession {

    public static final String USER_ID = "rentalhub.demoUserId";

    private DemoSession() {
    }

    /** The signed-in demo user, or null. Never creates a session just to find out. */
    public static Long userId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        return session.getAttribute(USER_ID) instanceof Long id ? id : null;
    }

    public static void signIn(HttpServletRequest request, long userId) {
        // A new session id on every change of user: the classic defence against session
        // fixation, where someone plants a session id and waits for it to be used.
        request.getSession(true);
        request.changeSessionId();
        request.getSession().setAttribute(USER_ID, userId);
    }

    public static void signOut(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }
}
