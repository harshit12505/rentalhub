# 07 — Photos on S3, three languages, GraphQL, OpenAPI and Postman

**What Phase 7 built:**
- **Listing photos**, uploaded by the host to S3 (or any S3-compatible store), checked by
  their actual bytes, and served back by the app. With no storage configured, an upload is
  a clear 503 and nothing else changes.
- **Three languages.** Every message the API can produce — business rules, bean
  validation, Spring's own errors, labels inside messages, the AI's answers — in English,
  Hindi and Spanish, chosen by `?lang=`, a cookie, or `Accept-Language`.
- **GraphQL** alongside REST: search and detail queries, booking and review mutations, and
  `@BatchMapping` so a page of listings costs the same few queries however long it is.
- **OpenAPI and Swagger UI** at `/swagger-ui.html`, with a description and example payloads
  on every endpoint — and a test that fails the build if a new endpoint has neither.
- **A Postman collection** covering every endpoint, with an environment file — and a test
  that fails if an endpoint is added without a request for it.

---

## 1. S3 in five minutes

📄 `storage/`, `config/S3Config.java`

**S3** (Amazon's Simple Storage Service) stores *objects*: a bag of bytes under a *key*,
inside a *bucket*. There are no real folders — `listings/12/0b6b….jpg` is one key that just
happens to contain slashes. You put an object, get it, delete it. That is nearly all of it.

Why a store like this and not the database or the server's disk:

| Where | Why not for photos |
|---|---|
| A `bytea` column in Postgres | Every photo read goes through the database, backups balloon, and the connection pool is tied up streaming images. |
| The server's own disk | On Render (and most container platforms) the disk is wiped on every deploy, and a second instance would not see the first one's files. |
| S3 | Built for exactly this: cheap, durable, any size, readable from anywhere. |

**The bucket stays private.** Browsers don't fetch photos from S3; they fetch
`/images/listings/…` from the app, which reads the object and streams it back. Three reasons:

1. AWS blocks public buckets by default, and turning that off is a classic security mistake.
2. The same code works against MinIO, LocalStack or any S3-compatible store.
3. A *pre-signed URL* (a temporary link S3 signs for you) expires — and listings are cached
   for minutes in Redis with their photo URLs inside them. An expired link in a cache is a
   broken image.

The cost is that the app carries the photo bytes. That's fine at this scale; the next step
would be a CDN (CloudFront) in front of `/images/**`.

**Keys are random and never reused:** `listings/<listing id>/<random UUID>.<ext>`. So a
photo's URL always means the same picture, which is why it can be cached for a year
(`Cache-Control: public, max-age=31536000, immutable`). The uploaded file's *name* is never
used: it is text a client chose, and `../../etc/passwd.jpg` has no business in a storage path.

**Credentials** use the AWS SDK's own names (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`,
`AWS_REGION`) plus `S3_BUCKET`. `S3_ENDPOINT` and `S3_PATH_STYLE` exist only for
S3-compatible servers: MinIO is addressed as `host/bucket` ("path style") rather than AWS's
`bucket.host`.

---

## 2. Trusting bytes, not names

📄 `storage/ImageFormat.java`

An upload arrives with a file name and a `Content-Type` header. Both are claims the client
makes: rename `invoice.pdf` to `photo.jpg` and a browser will happily send it as
`image/jpeg`. The first few bytes of a file are much harder to fake by accident, because
every format starts with a fixed **signature** (a "magic number"):

| Format | First bytes |
|---|---|
| JPEG | `FF D8 FF` |
| PNG | `89 50 4E 47 0D 0A 1A 0A` (`‰PNG` then line-ending bytes, chosen so a text-mode transfer visibly corrupts it) |
| WebP | `RIFF` + 4 bytes of size + `WEBP` (a WAV sound file is also `RIFF`, but says `WAVE` there) |

The rules in `ListingImageService`:

- the **bytes decide** the format; anything that isn't one of the three is refused
  (`image.type.unsupported`);
- a declared type that **contradicts** the bytes is refused too (`image.type.mismatch`) — a
  PNG sent as `image/jpeg` is a confused or lying client;
- no declared type, or the generic `application/octet-stream` (which is what `curl` sends for
  a `.webp`), is fine: then the bytes are the only evidence anyway;
- the stored content type and the key's extension come from the detected format.

**Size** is capped twice, deliberately: Spring's multipart limit (5 MB, answered with a
translated 413) and the service's own check against the same setting, so the two can't
silently drift apart. `resolve-lazily: true` makes Spring read the upload only when the
controller asks for it, which is what lets a too-large file be refused by the API's own error
handling, in the caller's language, instead of by Tomcat.

---

## 3. The order of the steps is the design

📄 `service/ListingImageService.java`, `service/ListingImageUpdates.java`,
`service/ImageObjectCleaner.java`

A file store and a database cannot share a transaction. It's the Phase 5 payment problem in
miniature, and it gets the same answer — a small **saga**:

**Uploading:**
1. check everything that can be checked first — storage configured, listing exists, the
   acting user is its host, fewer than 10 photos, the file is a real JPEG/PNG/WebP under
   5 MB — so a refusal costs no upload;
2. upload the file (a network call, so no transaction is open);
3. insert the row, in one short transaction;
4. if that insert fails, **delete the file again** (the compensating action).

**Removing** goes the other way round: delete the row (and commit), *then* delete the file,
from an `AFTER_COMMIT` listener. Deleting a whole listing does the same for all its photos.

Why that order? Whatever fails, the worst thing left behind is a file nobody points at —
a few kilobytes, logged as `image.orphaned` with its key. The other order could leave a
listing showing a photo that no longer exists.

**Two uploads at the same moment.** The listing is loaded with the same
`OPTIMISTIC_FORCE_INCREMENT` lock as a booking (Phase 3), so the two take turns: they can't
both squeeze under the 10-photo limit or both take position 3. The loser's file is deleted
and it gets a 409. One lesson from building it: Hibernate applies that lock mode to *every*
entity the query loads, so the query must load only the listing — joining the host or the
photos in the same query fails with "has no version and may not be locked".

---

## 4. Internationalisation (i18n)

📄 `config/WebConfig.java`, `web/LanguageParameterFilter.java`,
`messages.properties`, `messages_hi.properties`, `messages_es.properties`

**i18n** (18 letters between the i and the n) means building an app so its text can be
swapped per language without touching code. **l10n** (localisation) is the actual
translating. This project was ready for it from Phase 1: every exception carries a message
*key*, never a sentence.

**Which language answers a request** — `LocaleResolver`, in `WebConfig`:

1. `?lang=hi` on any request wins, and is remembered in a `rentalhub-lang` cookie;
2. otherwise that cookie;
3. otherwise the best match in `Accept-Language` (`hi-IN,hi;q=0.9,en;q=0.8` → Hindi);
4. otherwise English.

Only English, Hindi and Spanish are ever chosen. `fr-FR` becomes English — not a French
locale for which there are no messages, which would still print English but format numbers
the French way.

**Every kind of message follows it:**

| Message | Where the text comes from |
|---|---|
| Business rules (`property.notFound`) | the key on the exception, resolved by `GlobalExceptionHandler` |
| Bean validation (`@NotBlank(message = "{validation.required}")`) | the validator, which resolves through the same `MessageSource` |
| A label inside a message ("*Plot area* is required") | a `MessageSourceResolvable` argument, resolved in the same language |
| Spring's own errors (missing header, bad parameter, unreadable JSON, file too large, wrong method, no such path) | `problemDetail.<exception class>` keys, with Spring's arguments in `{0}`, `{1}` |
| GraphQL errors | the same keys, resolved by `GraphQlErrorResolver` |
| AI answers | the same keys, resolved by `StatsService` and `RecommendationService` |

**Real translations, enforced.** `MessagesFilesTest` fails the build when:
- a key exists in one file and not another;
- a translation loses or renumbers a placeholder (a lost `{0}` silently drops the number from
  the sentence);
- a Hindi value contains no Devanagari, or a Spanish value is identical to the English (bar
  three words that really are the same: *Villa*, *Gas*, *No*);
- a message with arguments contains a lone apostrophe (MessageFormat treats `'` as the start
  of a quoted section and swallows the rest of the text);
- a key thrown somewhere in the code is missing from the files.

**Two bugs worth remembering** (both in the project log):
- My resolver first computed the language once, when the request arrived — before `?lang=`
  had been read — so bean validation and the AI answers stayed in the old language. Spring's
  own resolver is *lazy* for exactly this reason; mine has to be too.
- `?lang=` was first read by Spring's `LocaleChangeInterceptor`, which only runs once a
  controller has been chosen, so a 405 or a 404 ignored it. A servlet filter sees every
  request. It reads the query string only on an upload, because `getParameter` would make
  Tomcat read the whole multipart body before the size limit gets a say.

---

## 5. GraphQL

📄 `resources/graphql/schema.graphqls`, `web/graphql/`

**GraphQL** is a query language for APIs. There is one endpoint (`POST /graphql`), a
**schema** describing every type and field, and the client sends a document asking for
exactly the fields it wants:

```graphql
query {
  searchProperties(filter: { city: "goa" }) {
    content { title pricePerNight host { fullName } images { url } }
  }
}
```

and gets back exactly that shape, in one round trip. Over REST, the same screen would need the
search, then the host and the photos of each listing, or an endpoint built for that screen.

| | REST | GraphQL |
|---|---|---|
| Endpoints | many, one per resource | one |
| Shape of the answer | fixed by the server | chosen by the client |
| HTTP status | tells you what happened | nearly always 200; errors are in an `errors` list |
| Caching | HTTP caches understand GET URLs | harder: everything is a POST |
| Good for | simple, cacheable, public APIs | screens that need many related things at once |

This project has both because the spec asks for both, and they share everything underneath:
the same services, rules, caches, currency handling and translated errors. A GraphQL
controller is a thin layer, exactly like a REST one.

**Money as a string.** GraphQL's built-in `Float` is a double — the type this project bans for
money. The schema defines a `Decimal` scalar that travels as `"12000.00"`, and is parsed from
its text on the way in, never via a double. `Date` and `DateTime` are scalars too.

**The N+1 problem, and `@BatchMapping`.** Ask for 20 search results and each one's host, and
a naive resolver runs 1 query for the page and then 1 per listing for its host: 21 queries,
41 with photos. That is the **N+1 problem**, and GraphQL makes it easy to walk into, because
the client decides which nested fields to ask for.

`@BatchMapping` methods are called once with *all* the listings on the page and answer with
one `IN (...)` query:

```java
@BatchMapping(typeName = "ListingCard")
public List<PropertyView.Host> host(List<PropertySummary> cards) {
    // one query: SELECT … FROM properties p JOIN p.host h WHERE p.id IN (:ids)
}
```

`GraphQlApiTest` proves it by counting SQL statements with Hibernate's statistics: a page of
8 listings with hosts and photos costs exactly as many statements as a page of 2.

**Errors.** GraphQL answers 200 even when a field fails, and lists the failure with a
`classification`. The standard ones (BAD_REQUEST, NOT_FOUND, FORBIDDEN) cover most cases;
the app adds CONFLICT, PAYMENT_FAILED and UNAVAILABLE for REST's 409, 402 and 503. Every error
carries the same translated message and `messageKey` as the REST API.

**The acting user** comes from the same `X-Demo-User-Id` header: an interceptor copies it into
the GraphQL context, where mutations read it. `/graphiql` is a browser page for trying
queries, with autocompletion from the schema.

---

## 6. OpenAPI, Swagger UI, and documentation that can't rot

📄 `config/OpenApiConfig.java`, `web/rest/ApiExamples.java`, the controllers

**OpenAPI** is a standard, machine-readable description of a REST API: every path, parameter,
body and response, as JSON. **Swagger UI** turns that into a web page where you can read the
API and try each call. **springdoc** generates the description by reading the controllers,
at `/v3/api-docs`, and serves Swagger UI at `/swagger-ui.html`.

What the code can't say, the controllers add with annotations: `@Tag` groups endpoints,
`@Operation` gives a summary and description, `@ApiResponse` lists each status with an
example payload. The example JSON lives in one class, `ApiExamples`, so the controllers stay
readable.

Documentation usually rots: someone adds an endpoint and forgets it. Here two tests stop that:
`OpenApiDocumentationTest` compares the endpoints Spring actually serves with the ones in the
description, and fails if any lacks a summary, a description or an example.

---

## 7. Postman

📄 `postman/RentalHub.postman_collection.json`, `postman/RentalHub.local.postman_environment.json`

**Postman** is an app for sending HTTP requests and keeping them organised. A **collection**
is a saved set of requests; an **environment** holds variables such as `{{baseUrl}}`, so the
same collection can point at your laptop or at Render. The collection's requests save ids from
their responses (`pm.collectionVariables.set('propertyId', …)`), so running the folders top to
bottom works on a fresh database: create a listing, upload its photo, book it, cancel it, ask
the AI about it, and do it all again over GraphQL.

`PostmanCollectionTest` compares the collection with the endpoints Spring serves, and fails if
an endpoint has no request.

---

## 8. How it's tested

- **Photos, against a real S3 server:** `ListingImageApiTest` runs in the "connected" context
  (`ConnectedIntegrationTest`), which has MinIO in Docker as well as the fake AI models, and
  inspects the bucket directly after each step: uploads, served bytes, refused files, the
  10-photo limit, removal, and deleting a listing. `ListingImageServiceTest` makes each step
  fail on purpose (the insert, the store) to prove the compensating delete.
  `ImagesSwitchedOffTest` checks the clean 503 in the ordinary, credential-free context.
- **Languages:** `MessagesFilesTest` (the files themselves), `LanguageApiTest` (the
  resolution rules end to end), `FrameworkErrorsApiTest` (Spring's own errors).
- **GraphQL:** `GraphQlApiTest`, over HTTP, including the query-count proof.
- **Docs and Postman:** `OpenApiDocumentationTest`, `PostmanCollectionTest`.

---

## Interview questions — practise answering these aloud

**"How do you validate an uploaded file's type?"**
By its first bytes, not its name or its declared content type, which are both claims the client
makes. JPEG, PNG and WebP each start with a fixed signature. A declared type that contradicts the
bytes is refused, and the stored type and file extension come from the bytes.

**"Why not store images in the database?"**
Every read would go through the database and its connection pool, and backups would balloon.
Object storage is built for large blobs: cheap, durable, and servable at scale. The database keeps
only the key and the URL.

**"The upload succeeded but the database insert failed. What happens?"**
The file is deleted again — a compensating action, as in a saga. Uploads go "file first, then row",
removals go "row first, then file after the commit", so the worst possible leftover is an unused
file, never a listing pointing at a missing photo.

**"Why serve photos through the app instead of a public bucket or pre-signed URLs?"**
The bucket stays private, any S3-compatible store works, and the URL never expires — which
matters because listings are cached with their photo URLs inside. The cost is bandwidth through
the app; a CDN is the next step.

**"How does your app pick a language?"**
`?lang=` (remembered in a cookie), then the cookie, then Accept-Language, then English, and only
among the three supported languages. Every message is a key resolved through Spring's
`MessageSource`, including bean validation and Spring's own errors.

**"How do you know the translations are complete?"**
A test compares the three files: same keys, same placeholders, no English left in the Hindi or
Spanish, no stray apostrophes, and every key the code throws present.

**"What is the N+1 problem, and how did you avoid it in GraphQL?"**
Loading a list, then one extra query per item for a related thing: 1 + N queries. `@BatchMapping`
receives all the items at once and loads the related data for all of them in one `IN (...)`
query. A test counts SQL statements to prove a page of 8 costs the same as a page of 2.

**"When would you choose GraphQL over REST?"**
When clients need many related things in different shapes for different screens — mobile and
web, say — and round trips are expensive. REST is simpler, maps onto HTTP caching and status
codes, and is better for simple or public APIs.

**"Why is money a string in your GraphQL API?"**
GraphQL's Float is a double, and doubles can't represent most decimal amounts exactly. A string
survives every hop, and is parsed into a BigDecimal from its text.

**"How do you keep API documentation up to date?"**
It's generated from the code, and a test fails the build if an endpoint is missing from it or has
no description or example. The Postman collection has the same kind of test.

---

## Honest limitations

- **Photos pass through the app.** No CDN, no resizing or thumbnails, no stripping of EXIF
  metadata (which can include the GPS location where a photo was taken — worth removing before a
  real launch).
- **No virus scanning** of uploads. The format check stops non-images, not malicious images.
- **Orphaned files are logged, not collected.** A job that lists the bucket and removes files no
  row points at would close that gap.
- **The photo order is upload order.** There is no endpoint to reorder or choose the cover.
- **GraphQL's own protocol errors** (a query that doesn't match the schema) come from graphql-java
  in English; the app's errors are translated.
- **No query-cost limit on GraphQL.** A deeply nested query could be expensive; a real public API
  would cap depth and complexity.
- **The translations are mine, not a native speaker's.** The tests catch mechanical mistakes, not
  awkward phrasing.

---

## Try it yourself

The [hands-on guide](hands-on-guide.md), Parts 53–62: languages, a photo upload with and
without storage (MinIO in Docker stands in for S3), GraphQL in GraphiQL, Swagger UI, and the
whole Postman collection.

---

## YouTube for this phase (in order)

1. `aws s3 tutorial for beginners` and `minio tutorial`
2. `file signature magic numbers`
3. `spring boot i18n locale resolver`
4. `graphql vs rest` and `spring for graphql tutorial`
5. `graphql N+1 problem dataloader`
6. `springdoc openapi swagger spring boot`
7. `postman tutorial for beginners`

Clickable versions are in the [project log](project-log.md#6-youtube-study-plan--every-phase).
