# 06 — AI: embeddings, RAG and search that understands

**What Phase 6 built:**
- **Search by meaning.** Every listing is turned into a vector (a list of numbers standing
  for what it means) and stored in Postgres with pgvector, so "somewhere quiet with a garden"
  finds a listing that never uses those words.
- **Hybrid search.** The crisp parts of a question (city, budget, party size) become a real
  SQL `WHERE`; the fuzzy part is answered by vector similarity. Both, together, ranked.
- **A preference profile**, built by counting the guest's own favourites, bookings and
  reviews — no model involved — plus a "taste vector" that finds more like what they save.
- **Grounded answers (RAG).** Gemini writes the answer, but only from the listings it is
  handed, and every listing id in its answer is checked afterwards. Anything it invented is
  cut out.
- **Statistics mode.** "How much have I spent on bookings?" is answered by SQL, exactly, with
  no model call at all.
- **Favourites**, as a real API, because the profile and the taste vector are built from them.
- **It all degrades.** No key, a wrong key, nothing indexed yet, or the free tier exhausted:
  the endpoint still answers 200 with real listings and says plainly what it could not do.

---

## 1. The words, in plain language

| Term | What it actually means |
|---|---|
| **Embedding** | A list of numbers (here 768 of them) that stands for the meaning of a piece of text. Texts that mean similar things get lists that point in similar directions. |
| **Vector** | The list of numbers itself, treated as a direction in space. |
| **Cosine similarity** | How close two directions are: 1 is the same direction, 0 is unrelated. Its opposite, *cosine distance*, is `1 − similarity`; pgvector's `<=>` operator computes it. |
| **Vector store** | A table (plus an index) that can answer "which rows are closest to this vector?" quickly. Here: the `vector_store` table in Postgres, via the pgvector extension. |
| **Semantic search** | Searching by meaning rather than by matching words. |
| **RAG** | Retrieval-Augmented Generation: find the facts first, then let a language model put *those* facts into sentences. The model is never asked what it knows. |
| **Grounding** | Making sure everything the model says is traceable to the facts you gave it — and checking afterwards that it is. |
| **Hallucination** | The model stating something plausible and false, such as a listing that does not exist. |
| **Prompt** | The text sent to the model: instructions, then the question, then the facts. |
| **Token / quota** | Models bill by tokens (word fragments), and free tiers cap requests per minute. Both are why this phase tries hard not to call the model. |

**The one idea behind embeddings.** You cannot compare two sentences by comparing their
letters. But if you turn each sentence into a direction in a 768-dimensional space, in a way
that puts similar meanings in similar directions, then "how alike are these?" becomes simple
arithmetic — an angle. That is all an embedding model does, and all a vector store stores.

---

## 2. What gets embedded, and when

📄 `ai/ListingEmbeddingService.java`, `ai/ListingIndexUpdater.java`,
`scheduling/EmbeddingIndexJob.java`

A listing becomes one piece of text (`textFor`):

```
villa in Goa, India. Quiet garden villa. A calm hideaway with a shaded garden, hammocks
and birdsong. No traffic, no crowds; the beach is a ten minute walk down a lane.
Sleeps 6, 3 bedrooms, 2 bathrooms. 9000.00 INR per night. plotAreaSqm: 500.00. hasPool: true.
```

Three things worth noticing:

- **The type-specific attributes are in there** (`plotAreaSqm`, `hasPool`), and they arrive
  through `Property.typeAttributes()` — the same data-driven mechanism as everywhere else, so
  a new property type is indexed with no change to this class. No `if` on the type. Ever.
- **The price is rounded to the currency's own decimals** before being written. The same
  price arrives as `9000.00` from the factory and `9000.0000` from `NUMERIC(19,4)`; a text
  that changed with that would re-embed the listing for nothing. (It did, the first time —
  see the project log, problem 6.4.)
- **The price and capacity are in the text on purpose.** They make "cheap cabin for two"
  partly answerable by meaning. The *real* filtering still happens in SQL, because a number
  inside a sentence is a hint, not a promise.

**When it runs.** `ListingIndexUpdater` listens for the same `PropertyChangedEvent` the caches
listen for, `AFTER_COMMIT`, for the same two reasons: before the commit there is nothing true
to index, and embedding is a network call, which must not happen inside a transaction. It
never throws — the listing is already saved, and a failed embedding must not turn a successful
request into an error.

**The hash.** Embedding costs a call to Gemini, and the free tier allows only a handful a
minute. So the text is hashed (SHA-256), the hash is stored next to the embedding in
`listing_embeddings`, and a listing is re-embedded only when that hash changes. Saving a
listing unchanged, or changing only its availability date, costs nothing.

**The document id.** `UUID.nameUUIDFromBytes("rentalhub-listing-" + id)` — always the same
UUID for the same listing. So an edit *replaces* the document (delete by that id, then add)
and an interrupted index can never leave two versions of one listing in the store.

**The backfill job.** `EmbeddingIndexJob` runs every two minutes and embeds up to 20 listings
that have no embedding yet. It exists for two cases that are certain to happen:

1. every listing created while no key was configured — which is all of them until the day a
   key is added;
2. listings whose embedding call failed, for example because the rate limit was reached.

It works one listing at a time and logs a warning for each failure, so one bad listing (or one
rate limit) never abandons the rest of the batch.

---

## 3. pgvector, and why Flyway owns the table

📄 `db/migration/V4__ai_index.sql`

```sql
CREATE TABLE vector_store (
    id        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    content   text,
    metadata  json,
    embedding vector(768)
);
CREATE INDEX idx_vector_store_embedding ON vector_store USING hnsw (embedding vector_cosine_ops);

CREATE TABLE listing_embeddings (
    property_id  BIGINT PRIMARY KEY REFERENCES properties (id) ON DELETE CASCADE,
    document_id  uuid NOT NULL,
    content_hash CHAR(64) NOT NULL,
    embedded_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

- **`vector(768)`** is a pgvector column type. 768 numbers, not the model's default 3,072,
  because pgvector's indexes stop at 2,000 dimensions — and because a quarter of the numbers
  costs a quarter of the storage and comparison time for very little accuracy here.
- **HNSW** (Hierarchical Navigable Small World) is a graph index for "nearest neighbours".
  Exact nearest-neighbour search compares your query with every row; HNSW hops through a graph
  of neighbours instead, and finds *almost* the nearest ones in a fraction of the time. That
  approximation is fine: nobody can tell whether the 5th-best match was really the 6th.
- **`vector_cosine_ops`** tells the index we will compare by angle (`<=>`), not by straight-line
  distance. The index is only used by queries that ask the same way.
- **`listing_embeddings`** is ours, not Spring AI's: it maps a listing to its document, keeps
  the content hash, and gives the backfill job a cheap "which listings have no embedding?"
  query. `ON DELETE CASCADE` means a deleted listing cannot leave a mapping behind.
- **Spring AI can create its own schema** (`initialize-schema: true`). It is off here.
  Flyway owns the schema in this project, `ddl-auto` is `validate`, and two things creating
  tables is how environments drift apart.

---

## 4. The preference profile: no model needed

📄 `ai/PreferenceProfile.java`, `ai/PreferenceProfileService.java`

Everything in the profile is counting and averaging over the guest's own rows:

- the cities they save most,
- the price band they save in — converted into whichever currency most of their favourites
  use, so a band still means something for someone who saves places in three countries
  (display-only conversion, exactly as in Phase 5),
- the party size they usually book for,
- the words that keep coming up in what they saved and wrote (word counting, minus a small
  stop-word list),
- how many reviews they have written and the average rating they give.

Free, instant, reproducible, and available with no AI key at all. The model never sees their
rows — only the one-line summary the profile writes about itself:

```
This guest has saved 2 listing(s), mostly in Goa, priced between 6000.00 and 9000.00 INR a
night. They usually book for 2 guest(s). Words that keep coming up in what they like: garden,
quiet, beach.
```

That is deliberate: the model needs to know they like quiet gardens in Goa, not who they are.

---

## 5. Understanding the question: rules, not a model

📄 `ai/QueryParser.java`, `ai/ParsedQuery.java`, `ai/QueryParserTest.java`

Many tutorials would send the question to the model and ask it to return JSON filters. This
project does not, and the reason is worth being able to say out loud:

> What we need out of the sentence is three crisp things — a city, a budget, a party size. A
> small parser gets those right in microseconds, deterministically, with tests that pin every
> case. An LLM would add a second network call before any work starts, a second thing that can
> be rate-limited, and answers that differ between runs. The model is kept for the one job only
> it can do: writing the answer.

The rules:

| In the question | Becomes |
|---|---|
| a city name **that has listings** ("Goa"), longest match wins ("New Delhi" over "Delhi") | `city` |
| "under 5000", "below ₹7,500", "cheaper than $120", "up to 80 eur" | `maxPrice` + `currency` |
| "for 4", "6 guests", "sleeps 6", "a couple" — but **not** "for 3 nights" | `guests` |
| "like my favourites", "similar to the ones I saved" | the taste search |
| "but cheaper", with no number, when they have favourites | the middle of their usual band |
| a measure word **and** a subject about them ("how much … bookings", "average … reviews") | `Intent.STATS` |
| anything else | `Intent.RECOMMEND` |

Matching cities against the cities that actually exist is what keeps "beach" from being read
as a place. The fuzzy half of the question is not parsed at all — that is what the embeddings
are for.

---

## 6. Hybrid retrieval

📄 `ai/HybridRetriever.java`, `domain/repository/PropertySpecifications.java`

A vector search alone would happily offer a listing that was taken off the market this
morning, or one that costs three times the budget, because neither fact is in the embedded
text. A SQL search alone has no idea what "somewhere quiet near the beach" means. So:

```
question
   │
   ├─ rules ──────────────► city / maxPrice+currency / guests
   │
   ├─ embeddings ─────────► up to 50 candidate ids, with similarity 0…1
   │                        (or the taste vector, for "like my favourites")
   │
   └─ SQL over live rows ─► of those candidates: still active, right city,
                            within the per-currency ceiling, big enough,
                            and not the asker's own listing
                                   │
                                   └─► ranked: similarity + small profile boosts
                                       → the best 5
```

**Why the candidate list is wider than the answer** (50 vs 5): the SQL filters will drop some
of them, and a vector search that returned exactly five could easily return five that are all
sold out.

**The boosts** are deliberately small (0.05, 0.05, 0.03): a listing in a city they like, in
the price band they usually pay, with room for their usual party, moves up a little. A boost
must never be able to lift a listing that means nothing like the question above one that does.

**The budget is still Phase 5's budget.** `maxPrice` is converted into a ceiling per listing
currency (`PriceCeilings`, rounded DOWN) and compared in each listing's own currency, and the
answer carries `exchangeRatesUnavailable` when a rate was missing. Asking for "anywhere under
$60 a night" with 1 USD = 95.96 INR really does exclude a ₹6,000 listing.

**No transaction is open** while any of this happens: embedding the question is a network
call, and the Phase 5 rule has not changed.

---

## 7. The taste vector: "more like the ones I saved"

📄 `ai/EmbeddingIndexStore.java`

The average of several embeddings is a point near everything they have in common. So the
average of a guest's favourites stands in for their taste — with no model call at all, because
Postgres can do the averaging:

```sql
WITH taste AS (
    SELECT avg(v.embedding) AS centre
    FROM vector_store v
    JOIN listing_embeddings e ON e.document_id = v.id
    WHERE e.property_id IN (:favourites)
)
SELECT e.property_id, 1 - (v.embedding <=> (SELECT centre FROM taste)) AS score
FROM vector_store v
JOIN listing_embeddings e ON e.document_id = v.id
WHERE e.property_id NOT IN (:favourites)
ORDER BY v.embedding <=> (SELECT centre FROM taste)
LIMIT :limit
```

No vector is ever loaded into Java, and the listings they already saved are excluded — they
know about those.

---

## 8. Statistics: the questions a model must never answer

📄 `ai/StatsService.java`

"How much have I spent on bookings?" has exactly one right answer, and it is a sum the
database can do. Handing the rows to a model to add up would be slower, cost quota, and
occasionally be wrong in a way that looks completely confident.

So `QueryParser` routes those questions to `StatsService`, which answers them in SQL and
writes the sentence from `messages.properties` (so it is translated like everything else):

| Question | Answer |
|---|---|
| how many listings have I saved? | You have saved 2 listing(s), mostly in Manali, Goa. |
| what do I usually pay for my favourites? | Your saved listings cost between 6,000.00 and 9,000.00 INR a night. |
| how much have I spent on bookings? | You have not paid for any bookings yet. |
| how many stays have I booked? | You have made 0 booking(s): 0 confirmed and 0 cancelled. |

**Money across currencies.** Spend is summed *per currency* in SQL, then converted into one
currency for display if — and only if — every one of them has a rate. Otherwise they are
listed side by side ("12,000.00 INR, 90.00 EUR"), because a total that quietly dropped the
euros would be a lie.

This is the whole phase in one sentence: **use the model for language, and the database for
facts.**

---

## 9. The prompt, and the grounding check

📄 `ai/RecommendationService.java`

The prompt has three parts: the instructions, the guest in one line, and the listings the
answer may talk about — each shown as `[id] Title — city, country — price currency per night
— sleeps N, …`.

The instructions say, among other things: refer to a listing by its bracketed id; never invent
a listing, a price, a place or a feature; if none of them suit, say so; at most four sentences.

**But instructions are not a guarantee.** So the answer is read back:

```java
[1] real    → kept
[999] not offered → removed from the text; logged as ai.answer.ungrounded
every id invented → the whole answer is thrown away, and the app writes the answer itself
```

An answer that mentions no id at all is kept, because "nothing here fits your budget" is a
good answer that invents nothing.

Why this matters more than the prompt wording: a prompt is a request, a check is a rule. A
model that makes something up cannot get it in front of a guest, whatever the prompt said, and
whatever version of the model is deployed next month.

The listings in the response are the same listings that were in the prompt, so a client that
does not trust the text can ignore it and show the cards.

---

## 10. Degrading, in every direction

The spec's rule for this project is absolute: a missing credential may cost you its own
feature and nothing else. What happens in each case — all of it verified, most of it tested:

| Situation | What the guest gets | Flags |
|---|---|---|
| No `GEMINI_API_KEY` at all | Ordinary filtered search, best five, and "Search by meaning is off because no Gemini key is configured." | `aiUsed=false`, `semantic=false` |
| A wrong or revoked key | The same, plus warnings in the log (`ai.search.failed`, `ai.answer.failed`, with the provider's message) | `aiUsed=false`, `semantic=false` |
| Key fine, nothing embedded yet | Filtered search, "No listing has been indexed for search by meaning yet." The backfill job fixes it within minutes | `aiUsed=false`, `semantic=false` |
| Embeddings fine, chat quota exhausted | The right listings, ranked by meaning, with a plain sentence instead of written prose | `aiUsed=false`, `semantic=true` |
| The model invents listings | Its answer is discarded or cleaned; real listings unchanged | `aiUsed=false` |
| Nothing matches the filters | "Nothing on the site matches that. Try a wider budget, another city, or fewer guests." | — |
| A statistics question | An exact answer from SQL, with or without a key | `aiUsed=false` |

**How "no key" is switched off at all.** 📄 `ai/AiEnvironmentPostProcessor.java`

Spring AI would otherwise build a chat model, an embedding model, and a `PgVectorStore` that
takes the embedding model as a constructor argument. The Gemini client refuses to be built
without a key, and that happens while the context is being created — the application would not
start at all. So an `EnvironmentPostProcessor` runs before any bean exists, just after the
configuration files are read, and when the key is blank it sets, as defaults:

```properties
spring.ai.model.chat=none
spring.ai.model.embedding=none
spring.ai.model.embedding.text=none
spring.ai.vectorstore.type=none
spring.autoconfigure.exclude=…GoogleGenAiEmbeddingConnectionAutoConfiguration
```

Those beans then never exist, and everything in `ai/` asks `AiAvailability` first. Two details
cost an hour each and are worth remembering: in **Boot 4** the registration name is
`org.springframework.boot.EnvironmentPostProcessor` in `META-INF/spring.factories` (the old
`org.springframework.boot.env` spelling and its `.imports` file are silently ignored), and the
Gemini *embedding connection* auto-configuration has no property switch of its own, so it has
to be excluded outright.

---

## 11. The API

```
PUT    /api/properties/{id}/favorite     → 204   (idempotent: saving twice leaves one)
DELETE /api/properties/{id}/favorite     → 204   (removing one that is not saved is fine)
GET    /api/favorites?currency=USD       → the saved listings, newest first
GET    /api/recommendations?q=…&currency=USD
```

`/api/recommendations` always answers **200**. The interesting part of the body:

```json
{
  "question": "somewhere quiet with a garden in Goa for 2",
  "intent": "RECOMMEND",
  "answer": "…",
  "suggestions": [ { "listing": { … }, "similarity": 0.62 } ],
  "aiUsed": false,
  "semantic": false,
  "exchangeRatesUnavailable": false
}
```

`similarity` is exposed on purpose: anyone reviewing this project should be able to see *why*
a listing was suggested. A `PUT` for saving (rather than `POST`) because it says "let this
listing be saved", which is true however many times you send it.

---

## 12. How it's tested

📄 `ai/*Test.java`, `support/FakeAiModels.java`

**No test needs a Gemini key.** A real key would make the build depend on a secret, cost
quota, answer differently every run, and fail whenever the network does.

- **Unit tests** for everything rule-shaped: the query parser (city, budget, "for 3 nights" is
  not a party size, stats vs recommend), the embedded text and its hash, the statistics
  sentences, and the grounding check (an invented id is cut out; an answer that is all
  invented is thrown away).
- **`AiSwitchedOffTest`** runs in the ordinary integration context, which has no key: the app
  boots, a listing is created, nothing is indexed, and a question still returns real listings
  with a reason. That is the spec's "boots with no AI" requirement, as a test.
- **`AiRecommendationTest`** has its own context with **fake models**: an embedding model that
  counts words into 768 dimensions (so cosine similarity really does measure shared words) and
  a chat model the test can make misbehave. Everything under them is real — the real
  `PgVectorStore`, the real pgvector container, the real SQL, the real taste vector. It proves
  indexing on commit, re-indexing only when the text changes, removal on delete, search by
  meaning, the budget filter, "like my favourites", an invented listing being overruled, and a
  model outage leaving the listings intact.

One thing the fake taught us: two texts with no words in common give a similarity of exactly
zero, and a vector store drops those, so the fake adds a small constant direction to every
vector. Real embeddings of two English sentences are never at right angles.

---

## Interview questions — practise answering these aloud

**"What is RAG, and why use it?"**
Retrieval-Augmented Generation: retrieve the relevant facts yourself, then let the model turn
*those* facts into sentences. You use it because a model does not know your data, and asking
it to guess produces confident nonsense. In RentalHub the retrieval is a hybrid search over
live rows, and the generation is one Gemini call with those listings in the prompt.

**"What is an embedding?"**
A list of numbers standing for the meaning of a text, arranged so that texts with similar
meanings point in similar directions. Comparing meanings then becomes measuring an angle.
Ours are 768 numbers per listing, from `gemini-embedding-001`.

**"Why 768 dimensions and not the model's default 3,072?"**
pgvector's HNSW and IVFFlat indexes stop at 2,000 dimensions, so 3,072 could not be indexed at
all. 768 also costs a quarter of the storage and comparison work, and the loss of accuracy on
listing-sized texts is not something a user can notice.

**"Why keep a separate vector store instead of searching text in Postgres?"**
We do not: the vector store *is* Postgres, with pgvector. That was the point of choosing it —
one database, one backup, one transaction story, and the vector search can be joined straight
against the live `properties` rows.

**"Why hybrid search rather than pure vector search?"**
An embedding is a snapshot of a listing's words. It does not know today's price, whether the
listing is still active, or how many people it sleeps. Vector search proposes what *means* the
right thing; SQL then keeps only what is *true* right now. Each half covers the other's
blind spot.

**"How do you stop it recommending listings that do not exist?"**
Three ways. The model is only ever shown a specific list of listings; it is told to refer to
them by bracketed id; and the answer is checked afterwards against the ids that were actually
sent. An invented id is removed, and an answer that names nothing real is thrown away and
replaced by one the application writes. The cards in the response come from the database, not
from the model.

**"How do you keep the index in step with the data?"**
Listings are re-embedded after the commit of any change (the same `AFTER_COMMIT` event the
caches use), a deleted or deactivated listing is removed from the index, and a sweep job picks
up anything that was missed. A content hash means an unchanged listing costs nothing.

**"Why not use an LLM to parse the question?"**
Because what needs parsing is three crisp things, and a small deterministic parser gets them
right with no network call, no quota, no variance between runs, and unit tests for every case.
Adding a model there would add a second failure mode before any work had started.

**"What did you do about cost and rate limits?"**
Nearly everything avoids the model: the profile is SQL, the query parser is rules, statistics
are SQL, and "like my favourites" is an average computed inside Postgres. Listings are embedded
once and only re-embedded when their text changes; the backfill job works in small batches. One
question costs at most one embedding call and one chat call.

**"What happens when the AI is down or unconfigured?"**
The endpoint answers 200 with real listings from ordinary filtered search, and says which part
was unavailable (`aiUsed`, `semantic`, and a translated sentence). No credential is required
for the application to boot or for any other feature to work.

**"How do you test AI code?"**
By making the model the only fake thing. The rules, the SQL, the vector store, the grounding
check and the degradation paths are all tested for real; the embedding and chat models are
replaced with deterministic fakes, one of which can be told to misbehave so the safety net is
exercised on purpose.

**"What is HNSW?"**
A graph-based approximate nearest-neighbour index. Instead of comparing the query with every
row, it walks a graph of neighbours, so search time grows roughly logarithmically instead of
linearly. It is approximate, which is the right trade for recommendations.

---

## Honest limitations

- **No re-ranking model and no chunking.** A listing is one document. Long descriptions are
  embedded whole, which is fine at this size; a real site with pages of text would split them
  into chunks and embed each.
- **The taste vector is a simple average.** It has no sense of recency and no negative signal
  (nothing learns from what a guest scrolled past or cancelled).
- **The themes in the profile are word counts**, not concepts: "beach" and "seaside" are two
  different themes to it.
- **The query parser only knows the phrasings it has rules for**, and only English. A question
  in Hindi is still answered — by the vector search, with the city and budget unread.
- **One embedding model, one chat model.** Switching provider means changing configuration and
  re-embedding everything, because embeddings from different models are not comparable.
- **No feedback loop.** Nothing records which suggestion was clicked or booked, so nothing
  improves the ranking over time.
- **The vector index is not partitioned by city**, so similarity is searched across the whole
  table before the SQL filters run. Fine for thousands of listings; a much bigger site would
  push the city filter into the vector query as metadata.
- **Costs are not metered.** There is no per-user cap on questions, which a public site would
  need on day one.

---

## Try it yourself

The [hands-on guide](hands-on-guide.md), Parts 46–52, walks through all of it: favourites, a
question with no key, the statistics answers, what a *wrong* key does, and — if you have a
Gemini key — the index filling up, search by meaning, and the grounding check.

## If you get a Gemini key

It is free: [aistudio.google.com/apikey](https://aistudio.google.com/apikey) → "Create API
key". Then, in the window you run the app from:

```powershell
$env:GEMINI_API_KEY = "AIza..."
```

Nothing else changes. On the next start the log says `ai.mode ready=true`, the backfill job
embeds the existing listings within a couple of minutes, and answers come back with
`"aiUsed": true` and `"semantic": true`. Without the key every part of this phase still runs,
which is the point.

---

## YouTube for this phase (in order)

1. `vector embeddings explained` and `cosine similarity explained`
2. `pgvector tutorial postgres` and `hnsw index explained`
3. `retrieval augmented generation explained`
4. `spring ai tutorial` and `spring ai vector store`
5. `llm hallucination grounding` and `prompt injection explained`
6. `recommendation system embeddings`
7. `gemini api getting started`

Clickable versions are in the [project log](project-log.md#6-youtube-study-plan--every-phase).
