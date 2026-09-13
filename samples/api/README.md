# Sample API requests

Request bodies for trying the API by hand (see `docs/learning/hands-on-guide.md`).
Send one with `curl.exe` from the repo root, for example:

```powershell
curl.exe -s -i -X POST http://localhost:8081/api/properties -H "Content-Type: application/json" -H "X-Demo-User-Id: 1" --data "@samples/api/villa.json"
```

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
