# Onboarding service (Java) — SDK implementation

One of two implementations in this repo: this one integrates with GBG Go v2
via the official Java Core SDK, `com.gbg:go-core-sdk` (alpha,
`0.2.0-alpha01`). See `../api-implementation/` for the sibling built on
hand-rolled HTTP, and the root `README.md` for how the two relate. The two
serve the identical REST contract — a front end cannot tell which one it is
talking to — and share every Go-agnostic package (`api/`, `session/`,
`go/mock/`, `config/ScreenPlanProperties` and friends) byte-for-byte.

The thin-proxy backend from *Market onboarding applications — front-end
handoff*, section 1: holds the GBG Go client credentials, mints and refreshes
access tokens, forwards interaction traffic, and keeps the session-to-instance
mapping. Neither a Go access token nor a client secret ever reaches the
browser.

## Architecture

```
com.gbg.samples.onboarding
├── api/            SessionController (the 7 REST endpoints) + error handling
│   └── dto/         wire types — identical to api-implementation's, byte-for-byte
├── config/          app.*, go.*, session.*, screen-plan.* configuration properties
├── session/         Session, SessionStore — the session-to-instance mapping
└── go/
    ├── GoClient.java        what the session layer needs from Go (unchanged seam)
    ├── mock/                canned-fixture client + per-market scenario scripts (unchanged)
    └── sdk/                 real GBG Go v2 integration via go-core-sdk
        ├── GoSdkProperties.java     credentials + endpoint selection (replaces GoProperties)
        ├── GoSdkAuthService.java    token mint + cache, via sdk.tokens().generate() (replaces GoTokenService)
        ├── GoSdkClient.java         journeys()/interactions() orchestration (replaces GoApiClient)
        └── SdkInteractionMapper.java  Go-shape <-> screen-DTO mapping (replaces DefaultInteractionMapper)
```

`GoClient` has two implementations, selected by `go.mode`, exactly as in
api-implementation:

- **mock** (default) — `MockGoClient`, the identical class, unmodified.
- **live** — `GoSdkClient`, calling `com.gbg.gocore.Go`'s `journeys()` and
  `interactions()` instead of hand-rolled `RestClient` calls. Every piece of
  business logic that made the raw-HTTP client correct — the four bounded
  per-instance caches, the document/selfie/document-back capture routing in
  `resolveAttachment()`, only marking a stage completed on a successful
  submit, and the three-way terminal check in `fetchState` — is ported
  structurally unchanged; see each class's javadoc for exactly what differs
  and why.

One deployment fronts exactly one market — `app.market` plus a Spring
profile per market sets the brand config, resource ID, and port.

### Why a whole second implementation

The user wanted the hand-rolled HTTP integration and the official SDK
integration comparable side by side rather than one replacing the other. The
SDK is alpha and its public docs name methods and types without documenting
their field shapes — see `docs/sdk-jar-inspection/FINDINGS.md` for how those
shapes were actually confirmed for `0.1.0-alpha01` (by pulling the SDK's own
`-sources.jar` from Maven Central and reading the real generated code, not
guessing), before any of the classes in `go/sdk/` were written. The same
method was used for the `0.2.0-alpha01` upgrade; see "Upgrading to
0.2.0-alpha01" below.

## Running

Needs **JDK 21** and Maven, same as api-implementation.

```
./run.sh                        # meridian-health, live  (the default)
./run.sh northbank mock         # any market, either mode
```

`run.sh` finds JDK 21, sources `.env.local`, and runs the right profile. The
long form still works if you prefer it:

```
mvn spring-boot:run -Dspring-boot.run.profiles=northbank         # :8091
mvn spring-boot:run -Dspring-boot.run.profiles=meridian-health    # :8092
mvn spring-boot:run -Dspring-boot.run.profiles=ridgeline-play     # :8093
```

**Ports are 8091/8092/8093** — ten higher than api-implementation's
8081/8082/8083 — specifically so both implementations can run side by side
against the same three front-end apps for comparison. `app.cors-allowed-
origins` is otherwise identical to api-implementation's (3000/3001/3002),
since neither implementation needs any frontend change.

Mock mode needs no credentials and no network, and behaves identically to
api-implementation's mock mode — they share the exact same `go/mock` package.

`?mock_scenario=<id>` on `POST /v1/sessions` reaches every designed outcome,
exactly as it does against api-implementation and the TypeScript mock.

## Running against real GBG Go

Copy `.env.example` to `.env.local`, fill in the four values, then
`./run.sh <market>`. Never commit that file; it is gitignored.

All three markets point at published journeys on the same tenant, with the
same resource IDs as api-implementation. Read "Known gaps in this SDK
integration" below before pointing this at a different tenant.

### Two tenant shapes

Unchanged from api-implementation — the SDK sits in front of the same two
tenant shapes, it doesn't remove the need to configure for them:

| | Public platform | `gbggo4-demo` (fabric nonprod) |
| --- | --- | --- |
| Token host | `api.auth.gbgplc.com` (PingFederate) | a Keycloak realm, no region segment |
| Grant | `client_credentials` + `scope=gbg.token` | `password` — id, secret, username **and** password |
| API host | `{region}.platform.go.gbgplc.com` | `gbggo4-demo-eu.…` — region in the tenant name |
| Token TTL | 3600s | 300s |

`GoSdkProperties` carries the same field set as api-implementation's
`GoProperties` for exactly this reason. `go.region` being one of `eu`/`us`/`au`
with no `go.base-url` override maps onto `Go.builder().serverIndex(0/1/2)`.
Otherwise (including every market profile here, and the fabric tenant) the
SDK gets `Go.builder().serverURL(...)` with `go.base-url` minus its trailing
`/v2/captain/`: since 0.2.0 the SDK adds that prefix to every operation path
itself, so passing the full base URL would request
`/v2/captain/v2/captain/...` (which Go answers with a misleading 401). Keep
writing `go.base-url` the way the API reference documents it, with
`/v2/captain/`; `GoSdkProperties.sdkServerUrl()` does the stripping.

## API docs

Identical to api-implementation — every running instance serves its own
OpenAPI 3 document and browsable UI:

```
GET /v3/api-docs           # the raw spec (JSON)
GET /swagger-ui/index.html # Swagger UI
```

`OpenApiContractTest` (copied verbatim from api-implementation) pins the
same endpoint/DTO shapes, so the two implementations are provably identical
from a front end's point of view.

## Session auth

Identical to api-implementation — `POST /v1/sessions` sets an HTTP-only,
`SameSite=Lax` cookie scoped to `/v1/sessions`; every other endpoint requires
it to match, or `410 SESSION_EXPIRED`. See api-implementation's README for
the full note on the `Secure` flag tracking the request's own scheme.

## Tests

```
mvn test
```

Go-agnostic suites copied verbatim from api-implementation
(`OnboardingFlowIntegrationTest`, `OpenApiContractTest`, `SessionTest`,
`ScreenPlanPropertiesBindingTest`, `ScreenPlanPropertiesValidationTest` and
others), plus suites replacing api-implementation's `DecidedStateTest` and
`DefaultInteractionMapperTest`:

- **`SdkDecidedStateTest`** — the go-core-sdk version of `DecidedStateTest`,
  against a `StateFetchResponse` built in the live shape (steps under
  `context.process.steps`, decision in `result`).
- **`SdkInteractionMapperTest`** — the go-core-sdk version of
  `DefaultInteractionMapperTest`. The `toInteraction` tests build an
  `InteractionFetchResponse` via the SDK's own builder; the `toRecord` tests
  build the same `StateFetchResponse` shape as `SdkDecidedStateTest`.
- **`GoSdkClientExceptionMappingTest`** — new; no equivalent exists in
  api-implementation. Exercises `GoSdkClient.call()`'s classification of
  both SDK exceptions, `APIException` and `ErrorResponse` (404/410 → session
  expired, 400/422 → validation failed, 429 → rate limited, everything else →
  upstream unavailable), plus an unrelated `RuntimeException` and a
  pass-through `OnboardingException`. `call()` is left package-private
  specifically so this test can reach it without a live Go call.

## Known gaps in this SDK integration

Everything below was either flagged as unverified in
`docs/sdk-jar-inspection/FINDINGS.md`, or something discovered while
writing `go/sdk/**` that goes beyond what the spike checked. Items (a) and (b)
have since been verified/fixed live (2026-09-18, Meridian Health, public
platform) — kept here with their resolution rather than deleted, since the
"what was uncertain and how it was actually resolved" is worth keeping.

**(a) `interactionAccess` equals `customerAccess` — CONFIRMED.** A full live
journey (personal details → contact → address → document capture → selfie
capture → decision) ran end to end through `interactions().submit()`/`.fetch()`
using the same cached token passed to both security schemes. No 401 at any
step. The two named security schemes resolve to the same bearer credential on
the real API, as FINDINGS.md Q8 guessed.

**(b) The state response dropped the decision and per-module results —
FIXED IN THE SDK (0.2.0).** Under 0.1.0, `GetJourneyStateResponseBody` had no
field for the wire response's `result` or `context.process`, and the SDK's
`ObjectMapper` (`FAIL_ON_UNKNOWN_PROPERTIES = false`) silently discarded
them, so every decision read as "Referred". The workaround was a
`RawStateBodyCapturingHttpClient` that buffered the raw bytes for a second
parse. 0.2.0's `StateFetchResponse` keeps `context` and `result` (as untyped
maps), so the workaround has been deleted and `SdkInteractionMapper.toRecord`
reads `context.process.steps`, `context.process.journey` and `result`
straight from the typed response. Checked against saved live state bodies
before the upgrade: every field the record screen uses survives
deserialization.

**(c) Document classification is read from the untyped step result.**
0.2.0 removed 0.1.0's typed `context.subject.documents[].classification`
(and its `GetJourneyStateClassification` class); `context` is now a plain
map. `SdkInteractionMapper.documentTypeLabel()` reads
`classification.name` from Document Classification's step result, the same
path api-implementation uses.

**(d) The biometric field-name rename is real — and the specific variant
FINDINGS.md guessed at turned out to be wrong on closer reading.**
FINDINGS.md flagged that `SubmitInteractionBiometric1` uses `face1Image`/
`face2Image` rather than today's `selfieImage`, and guessed Biometric1 was
the right variant "given the two-face-image shape matches a standard
liveness-check pair" — explicitly flagged there as unverified. Reading all
four `SubmitInteractionBiometric{1,2,3,4}` variants while writing
`SdkInteractionMapper.toSubmitRequest()` found:

| Variant | Fields | Fit for today's single-selfie capture |
| --- | --- | --- |
| `Biometric1` | `face1Image` **and** `face2Image`, both `@Nonnull` | Poor — this flow only ever captures one image |
| `Biometric2` | `selfieImage` **and** `anchorImage`, both `@Nonnull` | Poor — `anchorImage` is Document Classification's own internal output; this client never has one to send |
| `Biometric3` | `faceImage` (singular, `@Nonnull`) | Different field name |
| `Biometric4` | `selfieImage` only, `@Nonnull` | **Best fit** — same field name as today's raw shape, no second image required |

This implementation uses **`SubmitInteractionBiometric4`**, not Biometric1 —
a deliberate correction to FINDINGS.md's guess, made during implementation
rather than at the spike stage. **Confirmed live 2026-09-18** against Meridian
Health on the public platform: a full journey (personal details → contact →
address → document capture → selfie capture) submitted a selfie with this
exact shape, and the submit call was accepted with no error — a schema
mismatch on the biometric element would have surfaced as an immediate 400 on
that call, not later. Not yet confirmed against Northbank or Ridgeline Play's
own Liveness Verification modules specifically; if a live submit is ever
rejected on the biometric element on a market not yet exercised this way,
this is still the first thing to check.

**(e) Journey prefill only gets through where the SDK has a field for it.**
Api-implementation sends the prefill map as-is at `context.subject`. The
SDK's `StartJourneySubject` is fully typed (`identity`, `documents`,
`biometrics`, `consent`, ...) with no catch-all for extra properties, and
0.2.0 removed the untyped `data` field 0.1.0 had on `StartJourneyRequest`.
`SdkInteractionMapper.toStartRequest()` converts the prefill into
`StartJourneySubject`; any key (or nested field) the type has no field for
is not sent, and top-level keys that are dropped are logged. A prefill that
does not fit the type at all is logged and the journey starts without it.
None of the front ends sends a prefill today, so this hasn't been exercised
against a live journey.

**(f) An unmapped submit field now has nowhere to go.**
Api-implementation's `GoInteractionSubmitRequest` could always fall back to a
flat `subject.<key>` placement for a field name its lookup table didn't
recognise — a real capability, since the table is explicitly a sample
mapping invented before any journey existed. `SubmitInteractionRequest` (the
SDK's typed submit request) carries **no untyped catch-all field at all** —
confirmed by reading its source: only `instanceId`/`interactionId`/
`participants`/`context`. `SdkInteractionMapper.toSubmitRequest()` logs a
warning and drops an unrecognised key instead of guessing at a location for
it. A market whose journey needs a field outside the ones already mapped
(see the table in `SdkInteractionMapper`'s `FieldMapping.KNOWN`) needs a code
change here, not just a config one — this is a real, structural regression
in flexibility versus api-implementation, not a copy-paste gap.

**(g) `sdk.tokens().generate()`'s server override is host-only — the fabric
tenant's password grant may not work through the SDK at all.** This is a
finding beyond anything FINDINGS.md checked. Reading the generated
`PostAsTokenOauth2` operation class shows its request path is hardcoded to
`/as/token.oauth2`; the `serverURL` parameter `Tokens.generate(request,
serverURL)` accepts only replaces the scheme+host, never the path
(`Utils.generateURL(this.baseUrl, "/as/token.oauth2")`, unconditionally).
That matches the public platform's PingFederate endpoint exactly, so
`client_credentials` works as expected. Whether the fabric nonprod tenant's
Keycloak realm also happens to expose its token endpoint at that literal
path is unknown and cannot be checked by reading the SDK alone — Keycloak
realms conventionally use a `/realms/{realm}/protocol/openid-connect/token`-
shaped path, which would **not** match. If so, `GoSdkAuthService` will fail
to mint a token under the password grant no matter what `go.auth-url` is set
to, and there is no code-level workaround given the SDK's fixed path — this
would mean falling back to a raw HTTP mint for that one tenant shape only,
same as api-implementation already does for every tenant. Try `client_credentials`
tenants first; treat the password-grant path as genuinely at risk.

**(h) `go.scope` is inert under `client_credentials` via the SDK.**
Minor, but worth noting: `ClientCredentialsGrantRequest.scope` is a
fixed single-value generated enum (`Scope.GBG_TOKEN`, `"gbg.token"`) — there
is no way to send a different scope string through it, unlike the raw
form-POST which sent whatever `go.scope` was configured to. Harmless for the
public platform (`gbg.token` is the only valid value there anyway) and
irrelevant to the password grant, which never sent a scope on either
implementation.

**(i) A fetch with no interaction always shows the processing screen.**
Under 0.1.0, a fetch with nothing to render came back as a separate shape
with no `result`, so the decision could only come from `/state`. 0.2.0's
`InteractionFetchResponse` does carry `result`, but the behaviour is left
unchanged: a fetch with no `interaction` defers to a processing screen and
the front end's `/state` poll surfaces the outcome. That is never a wrong
answer, just occasionally one extra poll before the customer sees it.

**(j) Submit no longer reports in-band errors with a code.**
0.1.0 modelled a 200 response carrying `{status: "error", code, message}`
for both fetch and submit, and `GoSdkClient` classified it by code. 0.2.0
dropped both variants. For fetch and state, such a body still arrives as the
union's raw-JSON fallback, so `GoSdkClient.inBandError` reads its `code` and
classifies it as before (410 → session expired, and so on). For submit,
`InteractionSubmitResponse` has only a `status` whose one known value is
`success`, so the code is lost: `GoSdkClient.throwIfSubmitError` treats any
other status, or a missing body, as an upstream failure, and the stage is
not marked completed. Real Go errors arrive as HTTP 4XX/5XX and are handled
by `call()`.

## Upgrading to 0.2.0-alpha01

The upgrade from `0.1.0-alpha01` was a breaking change for this module. What
changed:

- **Response types.** Fetch returns `InteractionFetchResponse` (was
  `ResponseBody1`/`ResponseBody2`), state returns `StateFetchResponse` (was
  `GetJourneyStateResponseBody` with a `data` map), start returns
  `JourneyStartResponse` via `journeyStartResponse()`, and submit returns
  `InteractionSubmitResponse` via `interactionSubmitResponse()`.
- **Less typing on fetch.** `interaction`, `context` and `result` are maps,
  and `outstanding`/`instructions` are `Object`. `SdkInteractionMapper` reads
  `interaction.collects[].ref`/`spec` and the two string lists the same way
  api-implementation reads raw JSON.
- **Request signatures.** `interactions().fetch(security, view, body)` and
  `journeys().getState(view, body)` take a body object instead of a request
  holding the instance ID.
- **Security.** `FetchInteractionSecurity`/`SubmitInteractionSecurity` now
  have two bearer fields, `customerAccess` and `interactionAccess`, and no
  one-argument constructor. `GoSdkClient` sets `interactionAccess`, as 0.1.0's
  constructor did.
- **Exceptions.** Go's documented error codes (for example 400, 401, 404,
  500) now throw a new `ErrorResponse`, which extends `GoException` but not
  `APIException`. `GoSdkClient.authorized()` and `call()` now catch
  `GoException`; without that change, a 401 would have skipped the token
  retry and every Go error would have been reported as "something went
  wrong".
- **Removed workaround.** `RawStateBodyCapturingHttpClient` was deleted; see
  (b).
