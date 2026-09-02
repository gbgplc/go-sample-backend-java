# Onboarding service (Java)

The thin-proxy backend from *Market onboarding applications — front-end
handoff*, section 1: holds the GBG Go client credentials, mints and refreshes
access tokens, forwards interaction traffic, and keeps the session-to-instance
mapping. Neither a Go access token nor a client secret ever reaches the
browser. One TypeScript implementation of the same contract exists alongside
this one — a front end must not be able to tell which it's talking to.

## Architecture

```
com.gbg.samples.onboarding
├── api/            SessionController (the 7 REST endpoints) + error handling
│   └── dto/         wire types — must match onboarding-core's TS types field-for-field
├── config/          app.*, go.*, session.* configuration properties
├── session/         Session, SessionStore — the session-to-instance mapping
└── go/
    ├── GoClient.java        what the session layer needs from Go
    ├── mock/                canned-fixture client + per-market scenario scripts
    └── live/                real GBG Go v2 integration (token, journey/start,
                              interaction/fetch, interaction/submit, state/fetch)
```

`GoClient` has two implementations, selected by `go.mode`:

- **mock** (default) — `MockGoClient` runs the same scenario scripts as the
  front end's own mock transport, so this service runs standalone with no
  live Go credentials, mirroring the front-end's mock-mode requirement.
- **live** — `GoApiClient` calls the real GBG Go v2 API
  (`https://{region}.platform.go.gbgplc.com/v2/captain/`), using
  `GoTokenService` for client-credentials auth. Structurally complete against
  the documented v2 shapes, but not exercised against a live tenant while
  building this — there's no published journey or credential set available
  here to test against. `DefaultInteractionMapper` is the seam between Go's
  domain-element schema and this service's opinionated screen-kind/copy
  shape; it's a generic placeholder (see its Javadoc), not a finished mapping
  — that needs a real published journey's domain elements to map against.

One deployment fronts exactly one market — `app.market` plus a Spring
profile per market sets the brand config, resource ID, and port, matching
the three front-end apps one-to-one.

## Running

```
mvn spring-boot:run -Dspring-boot.run.profiles=northbank         # :8081
mvn spring-boot:run -Dspring-boot.run.profiles=meridian-health    # :8082
mvn spring-boot:run -Dspring-boot.run.profiles=ridgeline-play     # :8083
```

Defaults to `go.mode=mock`, so each starts up with no credentials and no
network dependency. Point a front-end app at one with:

```
NEXT_PUBLIC_ONBOARDING_TRANSPORT=rest
NEXT_PUBLIC_API_BASE_URL=http://localhost:8081
```

`?mock_scenario=<id>` on `POST /v1/sessions` (i.e. the same query param the
front end already appends to its own URL) reaches every designed outcome,
exactly as it does against the TypeScript mock.

To run against real GBG Go:

```
go.mode=live
GBG_CLIENT_ID=...
GBG_CLIENT_SECRET=...
go.region=eu   # or us / au
```

## API docs

Every running instance serves its own OpenAPI 3 document and a browsable UI:

```
GET /v3/api-docs           # the raw spec (JSON)
GET /swagger-ui/index.html # Swagger UI
```

The spec is generated from `SessionController`'s annotations
(`springdoc-openapi`), so it's always in sync with the actual endpoints — not
a hand-maintained doc that can drift. The title reflects which market the
running instance fronts (pulled from the same `AppConfigProperties` that
drives `GET /v1/config`), but the endpoint shapes are identical across all
three — that's the same "one contract, every deployment" rule the front end
and the TypeScript implementation follow too. `OpenApiConfig` also registers
the `onboarding_session` cookie as a named security scheme, so the lock icons
in Swagger UI accurately show which endpoints need it.

## Session auth

`POST /v1/sessions` sets an HTTP-only, `SameSite=Lax` cookie scoped to
`/v1/sessions` (front-end handoff, section 6, open decision — this is the
recommended option, implemented). Every other endpoint requires it to match
the session it was issued for; missing or mismatched → `410 SESSION_EXPIRED`.

The `Secure` flag is set from the *incoming* request's own scheme
(`request.isSecure()`), not hardcoded true. Chromium treats `localhost` as a
secure context, so a hardcoded `Secure` cookie happens to round-trip in a
browser during local dev — but curl, PowerShell's `Invoke-RestMethod`, and
most other HTTP clients don't carry that exception and silently drop it,
which turns every follow-up call into a `410`. Found this by testing outside
a browser, not by inspection — worth remembering if you ever "fix" this back
to a hardcoded `true`. Behind real HTTPS anywhere else, it's still `Secure`
correctly. To test with curl: `curl -c cookies.txt -b cookies.txt ...`.

## Tests

```
mvn test
```

`OnboardingFlowIntegrationTest` drives the whole Northbank flow through real
HTTP against `MockGoClient` — start, submit, idempotent resubmission, stale-
interaction rejection, cookie enforcement, and the terminal record — the
same contract `RestTransport` calls from the front end.

## What's a placeholder here

- **Attachment upload** (`POST /v1/sessions/{id}/attachments`) synthesises a
  reference rather than proxying to a real Go endpoint — document/selfie
  capture is explicitly a placeholder pending an SDK choice (front-end
  handoff, section 6).
- **The verification record composition** (`GET /v1/sessions/{id}/record`)
  is a design proposal being served, same as the mock front end — which
  fields a customer should see, especially the deciding module on a referral
  or decline, is a compliance decision the handoff doc flags as open.
- **Session storage** is in-memory and single-node — swap `SessionStore` for
  Redis or similar before running more than one instance.
