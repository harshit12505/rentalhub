package com.rentalhub.web.rest;

/**
 * Example payloads for the OpenAPI description (Swagger UI shows them as "Example Value").
 *
 * Kept together here so the controllers stay readable, and written from real responses of the
 * running application. They have to be compile-time constants to go into annotations, hence
 * text blocks rather than files.
 */
final class ApiExamples {

    private ApiExamples() {
    }

    static final String VILLA_REQUEST = """
            {
              "type": "VILLA",
              "title": "Sea breeze villa",
              "description": "Four bedrooms, two minutes from the beach.",
              "city": "Goa",
              "country": "India",
              "pricePerNight": 12000,
              "currency": "INR",
              "maxGuests": 8,
              "bedrooms": 4,
              "bathrooms": 3,
              "attributes": { "plotAreaSqm": "450", "hasPool": "true" }
            }""";

    static final String LISTING = """
            {
              "id": 1,
              "type": "VILLA",
              "title": "Sea breeze villa",
              "description": "Four bedrooms, two minutes from the beach.",
              "city": "Goa",
              "country": "India",
              "address": null,
              "pricePerNight": 12000.00,
              "currency": "INR",
              "maxGuests": 8,
              "bedrooms": 4,
              "bathrooms": 3,
              "active": true,
              "availableUntil": null,
              "host": { "id": 1, "fullName": "Asha Menon" },
              "images": [
                { "id": 3, "url": "/images/listings/1/0b6b3c3e-6a4f-4f59-9a0e-2b8f3c1d5e7a.jpg", "sortOrder": 0 }
              ],
              "attributes": { "plotAreaSqm": 450.00, "hasPool": true },
              "version": 0,
              "updatedAt": "2026-09-18T09:31:25.959849Z",
              "displayPrice": { "amount": 125.05, "currency": "USD", "rate": 0.010421, "ratesAsOf": "2026-09-18T00:02:31Z" }
            }""";

    static final String SEARCH_PAGE = """
            {
              "content": [
                {
                  "id": 1, "type": "VILLA", "title": "Sea breeze villa", "city": "Goa", "country": "India",
                  "pricePerNight": 12000.00, "currency": "INR", "maxGuests": 8, "bedrooms": 4, "bathrooms": 3,
                  "coverImageUrl": "/images/listings/1/0b6b3c3e-6a4f-4f59-9a0e-2b8f3c1d5e7a.jpg",
                  "displayPrice": null
                }
              ],
              "page": 0,
              "size": 20,
              "totalElements": 1,
              "totalPages": 1,
              "exchangeRatesUnavailable": false
            }""";

    static final String HISTORY = """
            [
              {
                "revision": 1, "changedAt": "2026-09-18T09:30:02Z", "changedBy": "user:1",
                "changedByName": "Asha Menon", "type": "CREATED", "changes": []
              },
              {
                "revision": 4, "changedAt": "2026-09-18T10:12:40Z", "changedBy": "user:1",
                "changedByName": "Asha Menon", "type": "UPDATED",
                "changes": [ { "field": "pricePerNight", "from": 12000.0000, "to": 11000.0000 } ]
              }
            ]""";

    static final String BOOKING_REQUEST = """
            {
              "propertyId": 1,
              "checkIn": "2027-03-10",
              "checkOut": "2027-03-13",
              "guests": 2,
              "paymentMethodId": "pm_card_visa"
            }""";

    static final String BOOKING = """
            {
              "id": 1,
              "property": { "id": 1, "title": "Sea breeze villa", "city": "Goa" },
              "guest": { "id": 2, "fullName": "Ravi Kumar" },
              "checkIn": "2027-03-10",
              "checkOut": "2027-03-13",
              "nights": 3,
              "guests": 2,
              "totalAmount": 36000.00,
              "currency": "INR",
              "displayTotal": null,
              "status": "CONFIRMED",
              "payment": { "status": "PAID", "provider": "SIMULATED", "reference": "sim_pi_3f2a9c", "refundReference": null },
              "createdAt": "2026-09-18T09:40:11.204518Z"
            }""";

    static final String BOOKINGS = "[" + BOOKING + "]";

    static final String CANCELLED_BOOKING = """
            {
              "id": 1,
              "property": { "id": 1, "title": "Sea breeze villa", "city": "Goa" },
              "guest": { "id": 2, "fullName": "Ravi Kumar" },
              "checkIn": "2027-03-10",
              "checkOut": "2027-03-13",
              "nights": 3,
              "guests": 2,
              "totalAmount": 36000.00,
              "currency": "INR",
              "displayTotal": null,
              "status": "CANCELLED",
              "payment": { "status": "REFUNDED", "provider": "SIMULATED", "reference": "sim_pi_3f2a9c", "refundReference": "sim_re_81d0" },
              "createdAt": "2026-09-18T09:40:11.204518Z"
            }""";

    static final String REVIEW_REQUEST = """
            { "rating": 5, "comment": "Lovely villa, great host." }""";

    static final String REVIEW = """
            {
              "id": 1,
              "propertyId": 1,
              "author": { "id": 2, "fullName": "Ravi Kumar" },
              "rating": 5,
              "comment": "Lovely villa, great host.",
              "createdAt": "2027-03-14T08:02:51Z"
            }""";

    static final String REVIEWS = "[" + REVIEW + "]";

    static final String FAVORITES = """
            [
              {
                "listing": {
                  "id": 1, "type": "VILLA", "title": "Sea breeze villa", "city": "Goa", "country": "India",
                  "pricePerNight": 12000.00, "currency": "INR", "maxGuests": 8, "bedrooms": 4, "bathrooms": 3,
                  "coverImageUrl": null,
                  "displayPrice": { "amount": 125.05, "currency": "USD", "rate": 0.010421, "ratesAsOf": "2026-09-18T00:02:31Z" }
                },
                "savedAt": "2026-09-18T09:31:25.959849Z"
              }
            ]""";

    static final String RECOMMENDATION = """
            {
              "question": "somewhere quiet with a garden in Goa for 2",
              "intent": "RECOMMEND",
              "answer": "I would take [1] for the shaded garden and the quiet lane to the beach.",
              "suggestions": [
                {
                  "listing": {
                    "id": 1, "type": "VILLA", "title": "Quiet garden villa", "city": "Goa", "country": "India",
                    "pricePerNight": 9000.00, "currency": "INR", "maxGuests": 6, "bedrooms": 3, "bathrooms": 2,
                    "coverImageUrl": null, "displayPrice": null
                  },
                  "similarity": 0.71
                }
              ],
              "aiUsed": true,
              "semantic": true,
              "exchangeRatesUnavailable": false
            }""";

    static final String STATS_ANSWER = """
            {
              "question": "how much have I spent on bookings?",
              "intent": "STATS",
              "answer": "You have spent 36,000.00 INR on 1 paid booking(s).",
              "suggestions": [],
              "aiUsed": false,
              "semantic": false,
              "exchangeRatesUnavailable": false
            }""";

    static final String IMAGE = """
            { "id": 3, "url": "/images/listings/1/0b6b3c3e-6a4f-4f59-9a0e-2b8f3c1d5e7a.jpg", "sortOrder": 0 }""";

    static final String INVALID_FIELDS = """
            {
              "type": "about:blank",
              "title": "The request was not valid",
              "status": 400,
              "detail": "Some fields are not valid.",
              "instance": "/api/properties",
              "errors": [
                { "field": "city", "message": "This field is required." },
                { "field": "title", "message": "This field is required." }
              ]
            }""";

    static final String BROKEN_RULE = """
            {
              "type": "about:blank",
              "title": "The request was not valid",
              "status": 400,
              "detail": "A villa needs a plot of at least 100 m².",
              "instance": "/api/properties",
              "messageKey": "property.villa.plotArea.min",
              "field": "attributes[plotAreaSqm]"
            }""";

    static final String NOT_FOUND = """
            {
              "type": "about:blank",
              "title": "Not found",
              "status": 404,
              "detail": "There is no listing with id 999.",
              "instance": "/api/properties/999",
              "messageKey": "property.notFound"
            }""";

    static final String NOT_ALLOWED = """
            {
              "type": "about:blank",
              "title": "Not allowed",
              "status": 403,
              "detail": "Only the host of this listing can change it.",
              "instance": "/api/properties/1",
              "messageKey": "property.notOwner"
            }""";

    static final String DATES_TAKEN = """
            {
              "type": "about:blank",
              "title": "Conflict with the current state",
              "status": 409,
              "detail": "Those dates are already booked. Please choose different dates.",
              "instance": "/api/bookings",
              "messageKey": "booking.dates.unavailable"
            }""";

    static final String DECLINED = """
            {
              "type": "about:blank",
              "title": "Payment failed",
              "status": 402,
              "detail": "The card was declined, and nothing was charged. Please try a different card.",
              "instance": "/api/bookings",
              "messageKey": "payment.declined"
            }""";

    static final String NO_IMAGE_STORAGE = """
            {
              "type": "about:blank",
              "title": "Temporarily unavailable",
              "status": 503,
              "detail": "Photo uploads are switched off, because no image storage is configured on this server.",
              "instance": "/api/properties/1/images",
              "messageKey": "image.storage.notConfigured"
            }""";

    static final String NOT_A_PHOTO = """
            {
              "type": "about:blank",
              "title": "The request was not valid",
              "status": 400,
              "detail": "Only JPEG, PNG and WebP photos can be uploaded.",
              "instance": "/api/properties/1/images",
              "messageKey": "image.type.unsupported",
              "field": "file"
            }""";
}
