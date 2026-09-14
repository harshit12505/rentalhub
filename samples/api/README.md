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
| `villa-moved-to-mumbai.json` | the villa, moved city (for PUT) | 200 on PUT |

## Bookings (`/api/bookings`)

```powershell
curl.exe -s -i -X POST http://localhost:8081/api/bookings -H "Content-Type: application/json" -H "X-Demo-User-Id: 2" --data "@samples/api/booking.json"
```

They assume the hands-on guide's data: listing 1 is Asha's villa (sleeps 8), listing 2 her
apartment (sleeps 2, ₹2,500 a night), and user 2 is the guest Ravi. The dates are in 2027; if you
read this after March 2027, move them into the future.

| File | What it is | Expected when POSTed |
|---|---|---|
| `booking.json` | apartment, 10–13 Mar 2027, 2 guests | 201, 3 nights, ₹7,500.00 |
| `booking-overlap.json` | apartment, 12–15 Mar (overlaps the one above) | 409 `booking.dates.unavailable` |
| `booking-back-to-back.json` | apartment, 13–16 Mar (starts on the first stay's check-out day) | 201 |
| `booking-too-many-guests.json` | apartment for 3 | 400 `booking.guests.tooMany` |
| `booking-checkout-before-checkin.json` | check-out 3 days before check-in | 400 `booking.checkOut.beforeCheckIn` |
| `booking-past-checkin.json` | January 2026 | 400 `booking.checkIn.past` |
| `booking-too-long.json` | 151 nights | 400 `booking.nights.max` |
| `booking-missing-fields.json` | no dates, 0 guests | 400 with an `errors` list |
| `booking-unknown-listing.json` | listing 999 | 404 `property.notFound` |
| `booking-villa.json` | villa, 1–6 Apr | 201 for a guest; 403 `booking.ownListing` for its host (user 1) |
| `booking-race-a.json`, `booking-race-b.json` | villa, 1–6 May and 4–9 May: send both at once | one 201, one 409 |
| `booking-price-race.json` | apartment, 10–13 Jun, for the staged retry | 201 at the new price |
| `booking-constraint-race.json` | apartment, 10–13 Jul, for the staged constraint race | 409 `booking.dates.justTaken` |
| `booking-rollback-race.json` | apartment, 10–13 Sep, for the same race ending in ROLLBACK | 201 |
