package com.rentalhub.web.rest;

/** HTTP headers the REST API reads. */
public final class ApiHeaders {

    /**
     * Who is acting. There is no login in this project: REST callers name the user in
     * this header, standing in for the "sign in as" switcher the web pages get in phase 8.
     */
    public static final String DEMO_USER_ID = "X-Demo-User-Id";

    private ApiHeaders() {
    }
}
