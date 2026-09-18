# Onboarding service (Java) — API implementation

One of two implementations in this repo: this one integrates with GBG Go v2
via hand-rolled HTTP. See `../sdk-implementation/` for the sibling built on
GBG's Java Core SDK, and the root `README.md` for how the two relate.

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
├── config/          app.*, go.*, session.*, screen-plan.* configuration properties
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
- **live** — `GoApiClient` calls the real GBG Go v2 API (see "Two tenant
  shapes" below — Meridian Health actually runs against the nonprod fabric
  variant, not the public platform default), using `GoTokenService` for
  token auth. `DefaultInteractionMapper` turns Go's flat, domain-element-
  shaped interaction into a screen, but carries no market-specific knowledge
  itself — which outstanding elements map to which screen, in what order,
  with what copy, is `ScreenPlanProperties` (a `screen-plan:` block in
  `application-<market>.yml`), config rather than code. Meridian Health's
  plan is real, verified content (see `application-meridian-health.yml`); a
  market with nothing configured there — Northbank and Ridgeline Play today
  — gets a generic form built from whatever Go reports outstanding, rather
  than a stall or a crash.

One deployment fronts exactly one market — `app.market` plus a Spring
profile per market sets the brand config, resource ID, and port, matching
the three front-end apps one-to-one.

## Running

Needs **JDK 21** and Maven. JDK 23 also builds and runs cleanly (verified
2026-09-08). The `oracleJdk-26.jdk` folder some checkouts carry is too new
for the Lombok version this build pins, and fails with symbol-not-found
errors across the mock package.

```
./run.sh                        # meridian-health, live  (the default)
./run.sh northbank mock         # any market, either mode
```

`run.sh` finds JDK 21, sources `.env.local`, and runs the right profile. The
long form still works if you prefer it:

```
mvn spring-boot:run -Dspring-boot.run.profiles=northbank         # :8081
mvn spring-boot:run -Dspring-boot.run.profiles=meridian-health    # :8082
mvn spring-boot:run -Dspring-boot.run.profiles=ridgeline-play     # :8083
```

Mock mode needs no credentials and no network. Point a front-end app at an
instance by copying `apps/<market>/.env.example` to `.env.local` in the
front-end repo — the ports pair up 3000/8081, 3001/8082, 3002/8083, and each
backend's CORS config allows exactly its own.

`?mock_scenario=<id>` on `POST /v1/sessions` (i.e. the same query param the
front end already appends to its own URL) reaches every designed outcome,
exactly as it does against the TypeScript mock.

## Running against real GBG Go

Copy `.env.example` to `.env.local`, fill in the four values, then
`./run.sh <market>`. Never commit that file; it is gitignored.

Only **Meridian Health** has a published journey. Northbank and Ridgeline
still carry placeholder resource IDs and will fail at journey start in live
mode until journeys exist for them.

### Two tenant shapes

The public documented platform and the nonprod *fabric* tenants disagree on
both auth and host layout, so `GoProperties` makes both configurable:

| | Public platform | `gbggo4-demo` (fabric nonprod) |
| --- | --- | --- |
| Token host | `api.auth.gbgplc.com` (PingFederate) | a Keycloak realm, no region segment |
| Grant | `client_credentials` + `scope=gbg.token` | `password` — id, secret, username **and** password |
| API host | `{region}.platform.go.gbgplc.com` | `gbggo4-demo-eu.…` — region in the tenant name |
| Token TTL | 3600s | 300s |

The token host having no region while the API host does is real, not a typo.
`go.base-url` can therefore be set outright rather than composed from
`go.region`.

Meridian's settings live in `application-meridian-health.yml`. Moving to a
different tenant means changing `auth-url`, `base-url`, `grant-type` and
`resource-id` — journey IDs are per-tenant, so ours will not exist on yours.

### Known issue: Data Verification

The Meridian journey has no Data Verification module, so no name, date of
birth or address is collected — document, selfie and consent only.

It was removed after it proved unusable on this tenant: the module declares
`FullName` as a required input and reports it "Not connected", the interaction
group will not offer `FullName` or `DateOfBirth` to add, and prefilling them in
`context.subject.identity` at journey start is silently discarded — the fetch
response returns an empty context. Reproduced across five publishes and two
separate journeys; every other module wires up correctly.

This needs raising with GBG rather than working around. The identity and
address mappings are still in `GoInteractionSubmitRequest`, so restoring the
module needs no code change here.

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

- `OnboardingFlowIntegrationTest` drives the whole Northbank flow through real
  HTTP against `MockGoClient` — start, submit, idempotent resubmission, stale-
  interaction rejection, cookie enforcement, and the terminal record — the
  same contract `RestTransport` calls from the front end.
- `OpenApiContractTest` fetches the live-generated `/v3/api-docs` spec and
  pins the endpoints and DTO field names a front end reads by name.
  `SessionController`'s Javadoc says these must match `RestTransport` to the
  field name; this test makes that an enforced check rather than a promise
  a human has to remember — a rename or dropped field fails here instead of
  surfacing later as a frontend-breaking change nobody connected back to it.
- `SessionTest`, `DefaultInteractionMapperTest` and
  `ScreenPlanPropertiesBindingTest` are plain unit tests (no Spring context)
  covering the idempotent-retry logic, screen-plan selection and module-
  verdict mapping, and that `application-meridian-health.yml`'s `screen-plan`
  block actually binds the content it looks like it does.

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
- **The default consent record URL** (`app.consent-url`, read by
  `GoInteractionSubmitRequest` when submitting the Consent module) is a
  placeholder (`https://meridianhealth.example/...`) — override it in
  `application-meridian-health.yml` before any real submission, since Go
  stores this URL as the auditable record of what was agreed to.
