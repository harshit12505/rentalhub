# Sample API requests

Request bodies for trying the API by hand (see `docs/learning/hands-on-guide.md`).
Send one with `curl.exe` from the repo root, for example:

```powershell
curl.exe -s -i -X POST http://localhost:8081/api/properties -H "Content-Type: application/json" -H "X-Demo-User-Id: 1" --data "@samples/api/villa.json"
```

## Listings (`/api/properties`)

| File | What it is | Expected when POSTed |
|---|---|---|
| `villa.json` | valid villa in Goa | 201 Created |
| `apartment.json` | valid apartment in Chennai, floor 3 | 201 Created |
| `cabin.json` | valid cabin in Manali | 201 Created |
| `studio.json` | valid studio in Bengaluru | 201 Created |
| `villa-two-guests.json` | villa for 2 guests | 400 `property.villa.guests.min` |
| `apartment-high-floor-no-lift.json` | 7th floor, lift not stated | 400 `property.apartment.elevator.required` |
| `apartment-bad-price.json` | ₹2500.125 | 400 `property.price.precision` |
| `cabin-unknown-heating.json` | heating "magic" | 400 `property.attribute.choice` |
| `invalid-blank-fields.json` | blank title and city | 400 with an `errors` list |
| `villa-renamed.json` | the villa, renamed (for PUT) | 200 on PUT |
| `villa-quiet-garden.json` | a calm garden villa in Goa, 9,000 INR (Phase 6) | 201 Created |
| `apartment-nightlife.json` | a noisy flat above the bars in Goa, 4,000 INR (Phase 6) | 201 Created |
| `cabin-snow-view.json` | a snow-view cabin in Manali, 6,000 INR (Phase 6) | 201 Created |
| `villa-moved-to-mumbai.json` | the villa, moved city (for PUT) | 200 on PUT |

## Bookings (`/api/bookings`)

```powershell
curl.exe -s -i -X POST http://localhost:8081/api/bookings -H "Content-Type: application/json" -H "X-Demo-User-Id: 2" --data "@samples/api/booking.json"
```

They assume the hands-on guide's data: listing 1 is Asha's villa (sleeps 8), listing 2 her
apartment (sleeps 2, ₹2,500 a night), and user 2 is the guest Ravi. The dates are in 2027; if you
read this after March 2027, move them into the future.

Since Phase 5 every booking also names the card to pay with, as a Stripe test payment-method
id. `pm_card_visa` always succeeds. With no Stripe key the app's payment simulator answers to
the same ids, so nothing is really charged.

| File | What it is | Expected when POSTed |
|---|---|---|
| `booking.json` | apartment, 10–13 Mar 2027, 2 guests | 201, 3 nights, ₹7,500.00 |
| `booking-overlap.json` | apartment, 12–15 Mar (overlaps the one above) | 409 `booking.dates.unavailable` |
| `booking-back-to-back.json` | apartment, 13–16 Mar (starts on the first stay's check-out day) | 201 |
| `booking-too-many-guests.json` | apartment for 3 | 400 `booking.guests.tooMany` |
| `booking-checkout-before-checkin.json` | check-out 3 days before check-in | 400 `booking.checkOut.beforeCheckIn` |
| `booking-past-checkin.json` | January 2026 | 400 `booking.checkIn.past` |
| `booking-too-long.json` | 151 nights | 400 `booking.nights.max` |
| `booking-missing-fields.json` | no dates, 0 guests, no payment method | 400 with an `errors` list |
| `booking-unknown-listing.json` | listing 999 | 404 `property.notFound` |
| `booking-villa.json` | villa, 1–6 Apr | 201 for a guest; 403 `booking.ownListing` for its host (user 1) |
| `booking-race-a.json`, `booking-race-b.json` | villa, 1–6 May and 4–9 May: send both at once | one 201, one 409 |
| `booking-price-race.json` | apartment, 10–13 Jun, for the staged retry | 201 at the new price |
| `booking-constraint-race.json` | apartment, 10–13 Jul, for the staged constraint race | 409 `booking.dates.justTaken` |
| `booking-rollback-race.json` | apartment, 10–13 Sep, for the same race ending in ROLLBACK | 201 |

## Payments and currencies (Phase 5)

Every way a payment can end, and prices in other currencies. Hands-on guide Parts 36–43 use
them in this order; the ids assume that guide's data.

| File | What it is | Expected |
|---|---|---|
| `booking-declined.json` | apartment, 20–23 Mar 2027, `pm_card_visa_chargeDeclined` | 402 `payment.declined`; nothing charged, the dates free again |
| `booking-after-decline.json` | the same dates, `pm_card_visa` | 201 |
| `booking-3ds.json` | apartment, 26–29 Mar, `pm_card_authenticationRequired` | 402 `payment.authenticationRequired` |
| `booking-provider-down.json` | the same dates, `pm_sim_providerDown` (simulator only) | 503 `payment.unavailable`, with `Retry-After: 60` |
| `booking-card-number.json` | a card number where a payment-method id belongs | 400, field `paymentMethodId` |
| `booking-no-answer.json` | villa, 1–6 Apr, `pm_sim_noAnswer` (simulator only) | 202, `PENDING`, until the reconciliation job confirms it |
| `studio-dubai.json` | a studio in Dubai at AED 300 (POST to `/api/properties` as a host) | 201 |
| `apartment-london.json` | a flat in London at £95 (POST to `/api/properties` as a host) | 201 |
| `booking-london.json` | the London flat (listing 4 in the guide), 5–8 May | 201; add `?currency=INR` to the URL to see the total in rupees too |

Any read also takes `?currency=INR|USD|EUR|GBP|AED`, which adds the price converted into that
currency (`displayPrice`, or `displayTotal` on a booking). It's for display only: what is
charged and stored is always the listing's own currency.

## Reviews (`/api/properties/{id}/reviews`, `/api/reviews/{id}`)

```powershell
curl.exe -s -i -X POST http://localhost:8081/api/properties/2/reviews -H "Content-Type: application/json" -H "X-Demo-User-Id: 2" --data "@samples/api/review.json"
```

Only a guest whose stay at the listing has ended may review it, once. The hands-on guide
creates such a stay with SQL, because the booking API refuses past dates.

| File | What it is | Expected |
|---|---|---|
| `review.json` | 5 stars and a comment | 201 after a stay; 403 `review.notStayed` before; 409 `review.alreadyReviewed` the second time |
| `review-edit.json` | 4 stars, a changed comment (for PUT `/api/reviews/{id}`) | 200 for the author; 403 `review.notAuthor` for anyone else |
| `review-bad-rating.json` | 6 stars | 400 with an `errors` list |

## Favourites and questions (Phase 6)

Favourites have no request body: `PUT` and `DELETE` say everything.

```powershell
curl.exe -s -o NUL -w "%{http_code}\n" -X PUT http://localhost:8081/api/properties/1/favorite -H "X-Demo-User-Id: 2"
```

| Request | What it does | Expected |
|---|---|---|
| `PUT /api/properties/{id}/favorite` | saves a listing; saving twice leaves one | 204 |
| `DELETE /api/properties/{id}/favorite` | removes it; removing one that isn't saved is fine | 204 |
| `GET /api/favorites?currency=USD` | the saved listings, newest first | 200 |
| `GET /api/recommendations?q=...&currency=USD` | a question in plain English | always 200 |

The last three listing samples above are worded to be told apart by *meaning*, which is what
the recommendations endpoint is for. Some questions to try with them:

| Question (`q=`) | What it shows |
|---|---|
| `somewhere quiet with a garden in Goa for 2` | the rules read the city and party size; the rest is answered by meaning |
| `somewhere in Goa under 5000` | the budget is a real SQL filter |
| `anywhere under $60 a night` (`&currency=USD`) | the budget converted into a ceiling per listing currency |
| `somewhere like my favourites` | the average of the saved listings' embeddings, computed in Postgres |
| `how much have I spent on bookings?` | answered by SQL; no model is called at all |

With no `GEMINI_API_KEY` the answer comes back with `"aiUsed": false` and a sentence saying
search by meaning is off. Nothing else changes.

## Photos (Phase 7)

Uploads are `multipart/form-data` with the photo in a part called `file`. They need image
storage (S3, or MinIO from `docker compose --profile photos up -d`); without it every upload
is a 503 that says so.

```powershell
curl.exe -s -i -X POST http://localhost:8081/api/properties/1/images -H "X-Demo-User-Id: 1" -F "file=@samples/api/photo.jpg"
```

| File | What it is | Expected when uploaded by the host |
|---|---|---|
| `photo.jpg` | a small JPEG (a beach at sunset, drawn for these samples) | 201 |
| `photo.png` | the same picture as a PNG | 201; 400 `image.type.mismatch` if sent with `;type=image/jpeg` |
| `photo.webp` | the same picture as a WebP | 201, although curl declares it `application/octet-stream` |
| `not-a-photo.jpg` | a PDF renamed to `.jpg` | 400 `image.type.unsupported` |

## GraphQL (Phase 7)

`POST /graphql` with one of these as the body; add `-H "X-Demo-User-Id: 2"` for the mutations.

```powershell
curl.exe -s -X POST http://localhost:8081/graphql -H "Content-Type: application/json" --data "@samples/api/graphql-search.json"
```

| File | What it asks |
|---|---|
| `graphql-search.json` | listings in Goa, with each one's host and photos (batch-loaded) |
| `graphql-detail.json` | listing 1 in full, with its price in USD and its attributes |
| `graphql-book.json` | book listing 1 for two nights in April 2027, paid with `pm_card_visa` |
| `graphql-review.json` | review listing 1: a FORBIDDEN error until a stay there has ended |

The same queries, and every REST endpoint, are also in the Postman collection in `postman/`.
