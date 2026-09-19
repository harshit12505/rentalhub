# 08 — The web pages: Thymeleaf, sessions and forms

**What Phase 8 built:** the website itself, served by the same Spring Boot app as the API.
- **Home:** search by city, guests, a price ceiling and a currency, with listing cards
  (photo, type, price, rating) twelve to a page.
- **A listing's page:** photos, details (including the type's own fields), reviews, a booking
  form paid with Stripe's test cards, "save to favourites", and, for its host, photo upload and
  removal.
- **List a place:** the host's form. Its type-specific fields are generated from the factory,
  so a new property type appears on it with no change to the page.
- **My bookings:** with totals in another currency on request, and cancel-with-refund.
- **Ask for ideas:** the Phase 6 recommendations, with the answer and the listings it is about.
- **"Sign in as":** a navbar menu that picks a demo user and keeps them in the session,
  replacing the API's `X-Demo-User-Id` header for the pages.
- **Three languages** from a navbar menu, and error pages that are never a stack trace.

No JavaScript framework and no build step: HTML rendered on the server, Bootstrap 5 from a CDN,
and one 25-line script.

---

## 1. Rendering on the server, and why here

📄 `web/mvc/`, `resources/templates/`

There are two ways to build a web front end:

| | Server-side rendering (this project) | Single-page app (React, Angular…) |
|---|---|---|
| Who builds the HTML | the server, for every page | JavaScript in the browser, from JSON |
| What the browser downloads | finished pages | an app, then data |
| Build tooling | none | Node, npm, a bundler |
| Good at | content, forms, first load, search engines | very interactive screens |

The spec fixes the choice (Thymeleaf, one container, no Node), and it suits the site: most pages
are "show some data, submit a form". The API from Phases 2–7 is still there for any other
client.

**Thymeleaf** is a *template engine*: an HTML file with extra `th:` attributes that the server
fills in. Its trick is **natural templating** — the template is valid HTML with sample content,
so it opens in a browser as a mock-up:

```html
<h1 th:text="${listing.title}">Quiet garden house</h1>
```

In the browser as a file: "Quiet garden house". Rendered by the app: the listing's real title.
`${…}` reads the *model* (the data the controller prepared), `#{…}` a message in the reader's
language, `@{…}` a link, `*{…}` a field of the form's object.

**Controllers are thin, as for REST.** A page controller calls the same services as the API, puts
the results in the `Model`, and names a template. Every rule is still in the services, so the
pages cannot book a listing the API would refuse.

**The services load everything a page needs** (`open-in-view: false` since Phase 1). A page
showing twelve cards needs twelve ratings; they come from one grouped query
(`ReviewService.ratingsFor`), not twelve — the N+1 problem again, this time on a web page.

---

## 2. One layout, no layout library

📄 `templates/layout.html`

Every page shares a frame: the `<head>`, the navbar, the notice from the last form, the footer.
Thymeleaf can do this on its own with a *fragment* (a named, reusable piece of a template) that
takes parameters:

```html
<!-- layout.html -->
<html th:fragment="page(title, content)"> … <main th:replace="${content}"></main> … </html>

<!-- home.html -->
<html th:replace="~{layout :: page(#{home.title}, ~{::main})}">
<body><main> …this page's content… </main></body>
```

The page replaces itself with the layout, handing over its title and its own `<main>`. There is a
popular "layout dialect" library for this, but it is one more dependency for something a
two-parameter fragment already does.

**Bootstrap 5 from a CDN, with SRI.** A CDN (content delivery network) serves the CSS and
JavaScript from servers near the reader. The version is pinned, and each `<link>`/`<script>`
carries an `integrity` hash (Subresource Integrity): if the file on the CDN were ever altered,
the browser would refuse to run it.

---

## 3. "Sign in as": a session without passwords

📄 `web/DemoSession.java`, `web/mvc/SessionController.java`, `web/RequestIdFilter.java`

HTTP forgets everything between requests. A **session** is how a server remembers a browser:
on the first need, Tomcat creates one, stores it in memory, and sends its id in a cookie
(`JSESSIONID`); every later request carries the cookie, and the server finds the session by it.

The demo has no passwords (the spec rules out Spring Security), so "signing in" is choosing a
user from the navbar. That puts their id in the session; `DemoSession.userId(request)` reads it
back. Three details make it more than a toy:

1. **A new session id on every sign-in** (`request.changeSessionId()`). This defeats *session
   fixation*: an attacker plants a session id in a victim's browser, waits for the victim to
   sign in on it, and then shares their signed-in session.
2. **No open redirect.** After signing in, the browser goes back to the page it came from,
   sent along as `returnTo`. Anything other than a path on this site — `https://evil.example`,
   `//evil.example` (a "protocol-relative" URL, which browsers treat as another site) — becomes
   `/`. Otherwise a link that *looks* like RentalHub could land on a phishing page.
3. **The audit trail still knows who.** `RequestIdFilter` sets the Envers actor from the
   header for the API and, failing that, from the session — so a booking made on the page is
   recorded as `user:2`, exactly as through the API.

### The cookie's settings

```yaml
server.servlet.session:
  cookie:
    same-site: lax
    http-only: true
  tracking-modes: cookie
```

- **Cookie only:** without this, Tomcat writes the session id into the address
  (`/;jsessionid=4B18…`) on the first redirect, because it doesn't yet know the browser keeps
  cookies. An id in an address leaks through logs, bookmarks and the `Referer` header — and here
  it also broke the home page (problem 8.3 in the project log). Found by clicking through in a
  browser; MockMvc never rewrites URLs.
- **HttpOnly:** page scripts cannot read the cookie, so an injected script cannot steal it.
- **SameSite=Lax:** the browser sends the cookie when someone *follows a link* to the site,
  but not with a form that *another site* posts to it.

That second setting is the defence against **CSRF** (cross-site request forgery): a stranger's
page containing a hidden form that posts to `/bookings/7/cancel`. Without SameSite, the victim's
browser would attach their session cookie and the cancel would go through. The usual defence is
a CSRF *token* (a secret in every form, checked by the server), which Spring Security provides —
and which this project doesn't use, by the spec. SameSite=Lax covers every modern browser; the
limitation is written down below.

---

## 4. Post, then redirect, then get — even when the form is refused

📄 `web/mvc/PageNotices.java`, `web/mvc/FormErrors.java`

If a page is the direct answer to a POST, pressing reload posts the form again: a second booking.
The standard fix is **Post/Redirect/Get (PRG)**:

```
POST /listings/5/book   →  302, Location: /bookings
GET  /bookings          →  200, the page
```

Reloading now repeats only the harmless GET. But a message such as "Booked: 2 nights, 24,000.00
INR paid." must survive the redirect. That is what a **flash attribute** is: stored in the
session for exactly one more request, then gone.

**Refused forms redirect too.** The textbook shows a refused form in answer to the POST, with
the errors. Doing that here caused a real bug, found while writing the tests: the address bar
then showed `/listings/5/book`, and the navbar's language links and "sign in as" (which reload
the current address) sent a GET there, which only answers POST — a 405 error. So a refused form
also redirects back to the listing, carrying *the form as typed and its errors* as flash
attributes:

```java
redirect.addFlashAttribute("booking", booking);
redirect.addFlashAttribute(BindingResult.MODEL_KEY_PREFIX + "booking", errors);
return "redirect:/listings/" + id + "#book";
```

The GET handler finds both in its model, exactly where a form shown in answer to the POST would
have them. The rule for the whole site: **every POST ends in a redirect**, so a page is always
the answer to a GET.

---

## 5. Forms and their errors

📄 `templates/property-detail.html`, `web/mvc/ListingPageController.java`

Spring MVC *binds* a form: it copies the posted fields onto an object (`BookingRequest`) and
records every problem in a **`BindingResult`**, field by field. Thymeleaf reads it back:

```html
<input type="date" th:field="*{checkOut}" th:errorclass="is-invalid">
<div class="invalid-feedback" th:errors="*{checkOut}">…</div>
```

`th:field` fills in the name, the id and the value (what the guest typed, even if invalid),
and `th:errors` prints that field's messages, in the reader's language.

Two kinds of problems reach a form:
- **Bean validation** (`@NotNull`, `@Size`): checked by Spring before the service is called.
- **Business rules** found by a service (dates taken, a villa's plot too small): they arrive as
  a `LocalizedException` with a message key and often a field. `FormErrors.reject` puts them on
  the `BindingResult` under that field, so they look exactly like validation errors — beside the
  input, from the same message files the API uses.

**A bug worth remembering.** The booking form doesn't send the listing id: the listing is the
one in the address. But `BookingRequest` is shared with the API, whose JSON body carries it, and
it is `@NotNull`. With `@Valid`, validation ran before the controller could set the id, so every
booking was refused — with the error on a field the form doesn't display, so the page showed no
error at all. The controller now sets the id from the address first, then validates explicitly.

**Dates:** `<input type="date">` sends `2027-03-10` whatever the page's language, so
`spring.mvc.format.date: iso` makes Spring read and write dates that way.

**Money** is shown by `MoneyFormat` (`${@money.format(amount, currency)}`): grouped the way the
reader's language groups numbers, with exactly the currency's decimals, and the currency code
after it (`24,000.00 INR`). Converted prices keep their `≈`: they are display-only (Phase 5).

---

## 6. The factory, on the page

📄 `templates/host-new-listing.html`, `web/mvc/HostListingController.java`, `static/js/listing-form.js`

The rule since Phase 1: adding a property type is an enum value, an entity, a creator, a
migration and translated labels — nothing else. The "list a place" form keeps that rule. It
never names a type. For every type the factory supports, the controller passes that creator's
`AttributeSpec`s, and the template renders one `<fieldset>` per type from them:

| `AttributeKind` | Input |
|---|---|
| `INTEGER` | `<input type="number" step="1">` |
| `DECIMAL` | `<input type="number" step="any">` |
| `BOOLEAN` | a select: *not stated* / Yes / No (a checkbox cannot say "not stated") |
| `CHOICE` | a select of the spec's choices, each labelled from the message files |

The inputs are named `attributes[plotAreaSqm]`, which Spring binds into the request's
`Map<String, String> attributes` — the same map the API's JSON fills. So the factory validates
a form exactly as it validates an API call, and its errors land beside the right input.

**Only the chosen type's fields are sent.** Every other fieldset is `disabled`, and a browser
never submits a disabled field. The script only swaps which fieldset is visible and enabled.
Without JavaScript the form still works (*progressive enhancement*): the server renders the
chosen type's fields, and a `<noscript>` button reloads the form for another type.

The same idea on the listing's page: its type-specific details come from
`Property.typeAttributes()` as label/value pairs, labelled from the messages.

**A test keeps it that way.** `PropertyFactoryTest.pagesKnowNoPropertyType` fails if any
template or script contains a property type's name or one of its attribute names. Even sample
text counts: it caught "Quiet garden villa" in a template's placeholder title.

---

## 7. Languages, on every page

📄 `web/mvc/PageModelAdvice.java`, `i18n/MessagesFilesTest.java`

All page text is in the three message files (135 new keys). The navbar's language menu links
to *this same page* with `?lang=` replaced, and names each language in itself — "हिन्दी",
"Español" — because someone who reads only Hindi is looking for Hindi, not for "Hindi".

`PageModelAdvice` is a `@ControllerAdvice`: its `@ModelAttribute` method runs before every page
controller and adds what the layout needs (who is signed in, who can be, the language links).
It is limited to the page controllers' package, so the API never pays for it.

**Thymeleaf doesn't fail on a missing message.** It prints `??detail.book_hi??` into the page.
So two checks exist:
- `MessagesFilesTest` reads every template for `#{key}`s, and adds the keys built from enum
  values (`#{bookings.status.__${b.status}__}` needs one per `BookingStatus`), then checks they
  all exist;
- every page test asserts the HTML contains no `??`.

---

## 8. When something goes wrong

📄 `web/mvc/PageExceptionHandler.java`, `web/mvc/PageErrorViewResolver.java`, `templates/error.html`

The API answers errors in JSON (Phase 7). The pages answer with an error page, in the reader's
language, with the site's navbar — and never a stack trace:

| What | Page |
|---|---|
| `/listings/9999` | 404, "There is no listing with id 9999." |
| `/listings/abc`, `?currency=XYZ` | 400, Spring's type error, translated |
| a guest trying a host's action | 403 |
| a photo over 5 MB | back to the listing, with the reason on top |
| `/no-such-page`, or a GET to a POST-only address | Spring Boot's own error page, drawn with our template |
| anything unexpected | 500, "Something went wrong on our side", with the request id to quote |

`PageExceptionHandler` covers errors inside page controllers. For addresses no controller
answers, Spring Boot's own error controller renders `error.html`; `PageErrorViewResolver` gives
that page the navbar too. Neither path runs the advice's model method, so both add the layout
themselves — and if the database is down (the likely cause of a 500), the error page still shows,
without the user menu.

---

## 9. Payments and photos on the page

**No card-number box.** The booking form offers Stripe's three test payment methods — succeeds,
declined, needs 3-D Secure — as a select. A real card form would use Stripe's own JavaScript
(Stripe Elements), which sends the card straight to Stripe and gives the server only a token:
card numbers must never touch our server (PCI DSS). That needs a publishable key and a browser
flow; the test ids show the whole saga without either. 3-D Secure is refused with a message, as
in Phase 5.

**Photos:** the host's upload form posts `multipart/form-data` to the same service as the API.
With no storage configured, the listing comes back with "Photo uploads are switched off…". A
file over 5 MB is refused before the controller runs (Spring reads the upload only when asked,
Phase 7), so `PageExceptionHandler` sends the host back to the listing with the message.

That last part needed one more setting, found only in a real browser. The upload is refused
from its declared size, before its bytes are read. Tomcat then reads and discards the unread
rest of the request, but by default only up to 2 MB (`max-swallow-size`); past that it closes
the connection. curl copes, but a browser still sending the file shows its own "connection
reset" page instead of ours. `server.tomcat.max-swallow-size: 50MB` covers any phone photo.

---

## 10. How it's tested

Page tests are MockMvc integration tests, like the API tests, but they post forms the way a
browser does and **follow the redirect** the way a browser does: the same session, the cookies
the response set (the language) and the flash attributes it carried (`PageTest.follow`).

| Test | Covers |
|---|---|
| `HomePageTest` | cards and ratings, filters kept across pages, Spanish, sign in/out, session id change, open-redirect guard, 404 and 400 pages |
| `ListingPageTest` | type fields in two languages, who sees which form, book → My bookings → cancel with refund, the audit actor, a refused booking in Spanish, a declined card, signing in first, reviews (and HTML escaping), favourites, upload with no storage, someone else's booking |
| `HostListingPageTest` | one fieldset per type from the factory, `?type=`, publish, errors in Hindi, a type rule beside its field, guests refused |
| `RecommendationPageTest` | the AI-off notice and the plain answer; signing in first |
| `ListingPhotosPageTest` | upload and remove against MinIO (connected context) |
| `PageModelAdviceTest` | the "current page" address, including on Spring Boot's error page |
| `MessagesFilesTest`, `PropertyFactoryTest` | template keys exist; no page names a property type |

What MockMvc cannot show — how the pages *look*, the script, a real browser's cookies, Spring
Boot's own error page — was checked by hand in a browser (hands-on guide, Parts 63–70).

---

## Interview questions — practise answering these aloud

**"Server-side rendering or a single-page app — how do you choose?"**
Server-side rendering for content and forms: fast first load, no build tooling, works without
JavaScript, simple to secure. A SPA for highly interactive screens, or when many clients share
one JSON API. Here the spec wanted one container with no Node, and the API still exists for
other clients.

**"What is Post/Redirect/Get, and why do refused forms redirect too?"**
After a POST the server answers with a redirect, so a reload repeats only a GET, never the
booking. Messages survive the redirect as flash attributes. A refused form redirects too, with
the form and its errors in the flash, so the address bar always shows a page that answers GET —
otherwise links built from the current address (the language menu) point at a POST-only URL.

**"How do you keep a user signed in without Spring Security?"**
A session: the server stores the user id against a random id sent in a cookie. The id changes
on every sign-in (against session fixation), the cookie is HttpOnly (scripts can't read it) and
SameSite=Lax (other sites' forms don't carry it).

**"What is CSRF, and how does your app defend against it?"**
Another site making the victim's browser submit a request here with their cookie. The standard
defence is a per-form token; this app, with no Spring Security, relies on the SameSite=Lax
cookie, which modern browsers enforce. A production app would add tokens.

**"What is an open redirect?"**
An endpoint that redirects to any URL it is given, which lets a link on your domain send people
anywhere. `returnTo` is accepted only as a path on this site; `//evil.example` is a classic trick
it rejects.

**"How does Thymeleaf protect against XSS?"**
`th:text` escapes HTML: a review saying `<b>quiet</b>` shows those characters instead of bold
text, and a `<script>` would not run. `th:utext` would not escape, and is never used on user
content. A test posts a review with HTML in it and checks it comes back escaped.

**"How does your form show the right fields for each property type without if-statements?"**
The controller passes each type's `AttributeSpec`s; the template renders one fieldset per type
from them, and a disabled fieldset is not submitted. A test fails if a template names a type.

**"How do you make sure no text is missing in a language?"**
The message-file test checks every `#{key}` a template uses, including keys built from enum
values, and every page test fails on Thymeleaf's `??key??` marker.

---

## Honest limitations

- **No CSRF tokens.** SameSite=Lax covers current browsers; very old ones ignore it. Spring
  Security's tokens are the standard fix, ruled out by the spec.
- **Sessions live in one server's memory.** A restart signs everyone out, and a second instance
  wouldn't share them; Spring Session with Redis is the usual next step.
- **No real card entry.** Stripe's test ids stand in for Stripe Elements; 3-D Secure is refused
  rather than completed in the browser.
- **Switching type or language on the "list a place" form** reloads it: what was typed is lost.
- **Bootstrap comes from a CDN**, so the pages look unstyled offline. Serving it from the app
  (a WebJar) would fix that at the cost of a bigger jar.
- **Accessibility was considered, not audited:** labels on every input, `aria-*` on toggles and
  disabled links, alerts with `role`; no screen-reader testing.

---

## Try it yourself

The [hands-on guide](hands-on-guide.md), Parts 63–70: the pages in a browser, in three
languages, from searching to booking, cancelling, reviewing, listing a place and adding a photo.

---

## YouTube for this phase (in order)

1. `thymeleaf spring boot tutorial`
2. `thymeleaf fragments layout`
3. `bootstrap 5 tutorial for beginners`
4. `post redirect get pattern`
5. `spring mvc form validation bindingresult thymeleaf`
6. `http session cookies explained`
7. `csrf attack explained` and `samesite cookie explained`
8. `open redirect vulnerability`
9. `xss cross site scripting explained`

Clickable versions are in the [project log](project-log.md#6-youtube-study-plan--every-phase).
