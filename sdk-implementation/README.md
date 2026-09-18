# Onboarding service (Java) — SDK implementation

One of two implementations in this repo: this one integrates with GBG Go v2
via the official Java Core SDK, `com.gbg:go-core-sdk` (alpha,
`0.1.0-alpha01`). See `../api-implementation/` for the sibling built on
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
SDK is alpha (`0.1.0-alpha01`) and its public docs name methods and types
without documenting their field shapes — see
`../spike/sdk-jar-inspection/FINDINGS.md` for how those shapes were actually
confirmed (by pulling the SDK's own `-sources.jar` from Maven Central and
reading the real generated code, not guessing), before any of the classes in
`go/sdk/` were written.

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

Only **Meridian Health** has a published journey (same tenant, same
resource ID, as api-implementation). Northbank and Ridgeline still carry
placeholder resource IDs and will fail at journey start in live mode until
journeys exist for them.

**This module has not been run against a live tenant** — there are no GBG
credentials available in the environment this was built in. Read "Known gaps
in this SDK integration" below in full before pointing this at a real
tenant; several pieces are the best-available reading of the SDK's generated
source rather than something a live call has confirmed.

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
with no `go.base-url` override maps onto `Go.builder().serverIndex(0/1/2)` —
confirmed to match the SDK's own `Go.SERVERS` array exactly (see
FINDINGS.md, Q7); any other tenant (including the fabric one) uses
`Go.builder().serverURL(go.base-url)` instead, same as before.

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

66 tests, all passing (`mvn test` output verified when this module was
built): 5 Go-agnostic suites copied verbatim from api-implementation
(`OnboardingFlowIntegrationTest`, `OpenApiContractTest`, `SessionTest`,
`ScreenPlanPropertiesBindingTest`, `ScreenPlanPropertiesValidationTest`), plus
three new suites replacing api-implementation's `DecidedStateTest` and
`DefaultInteractionMapperTest`:

- **`SdkDecidedStateTest`** — the go-core-sdk version of `DecidedStateTest`.
  Both original assertions ported, against a `GetJourneyStateResponseBody`
  built with the same step/result shape hand-written into its untyped `data`
  map (see "Known gaps" below for why that's where it has to live).
- **`SdkInteractionMapperTest`** — the go-core-sdk version of
  `DefaultInteractionMapperTest`. All ~26 original assertions ported, none
  dropped: the `toInteraction`/`currentCaptureIsDocument`/`stageFor` tests
  build a real `ResponseBody1` via the SDK's own builder (the most-confirmed
  shape from the spike); the `toRecord` tests build the same untyped-`data`
  shape as `SdkDecidedStateTest`.
- **`GoSdkClientExceptionMappingTest`** — new; no equivalent exists in
  api-implementation. Exercises `GoSdkClient.call()`'s classification of
  `APIException` (404/410 → session expired, 400/422 → validation failed,
  429 → rate limited, everything else → upstream unavailable), plus an
  unrelated `RuntimeException` and a pass-through `OnboardingException`.
  `call()` is left package-private specifically so this test can reach it
  without a live Go call.

## Known gaps in this SDK integration

Everything below was either flagged as unverified in
`../spike/sdk-jar-inspection/FINDINGS.md`, or something discovered while
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

**(b) Step/module timing and the journey decision are NOT in `data` at all —
CONFIRMED AND FIXED.** A live capture of ten consecutive `journeys().getState()`
polls against a genuine in-progress instance came back `data={}` every single
time, while `context` was populated. Root cause: `com.gbg.gocore.utils.JSON`'s
shared `ObjectMapper` sets `FAIL_ON_UNKNOWN_PROPERTIES = false`, and
`GetJourneyStateResponseBody` has no field for the wire JSON's top-level
`steps`/`result` keys — Jackson silently drops them rather than routing them
into `data` or erroring. This is a genuine gap in the SDK's current (alpha)
response model for this one operation, not a wrong guess about where to look.

Fix: `RawStateBodyCapturingHttpClient` wraps the SDK's own
`SpeakeasyHTTPClient` and, only for a `journey/state/fetch` request, buffers
the exact response bytes as they come off the wire — no extra network call —
before handing back an equally-readable response for the SDK's normal typed
parsing to continue as usual. `GoSdkClient.fetchGoState` then parses that same
buffer itself with `JSON.getMapper()` and passes the result into
`SdkInteractionMapper.toRecord`/`.carriesRealResult` as an explicit
`rawStateBody` parameter, falling back to `body.data()` (confirmed always
empty, but free to try) only if the buffer capture itself somehow comes back
empty. Verified live: `moduleRuns`, per-module timing, and the full journey
summary (name, reference, started/decided timestamps, total time) all
populate correctly now — previously every field derived from `data` was
silently absent, and every decision defaulted to "Referred" regardless of the
real outcome, since `mapDecision` (itself an exact, previously-proven-correct
port from `api-implementation`) was defaulting on a `null` classification it
should never have seen.

**(c) Document classification: typed path added, still falls back to the raw
map.** Unlike (b), there **is** a typed alternative for document
classification — `context.subject.documents[0].classification`, with
`category`/`type`/`subtype`/`countryName`/`year` fields — but no single
`name` string matching the old raw JSON's `classification.name`.
`SdkInteractionMapper.documentTypeLabel()` tries composing a label from the
typed fields first, then falls back to walking `data` for a raw
`classification.name`, per the task's "defensive, both paths" requirement.
Neither path is confirmed against a live response; a real one would settle
which (if either) is right, and whether the composed typed label reads
sensibly next to Go's actual `name` string.

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
rather than at the spike stage. It is still **unverified against a live
journey's `collects` response**, which is the only place the actual
discriminator a given Liveness Verification module expects would show up.
If a live submit is rejected on the biometric element, this is the first
thing to check.

**(e) `journeys().start()`'s prefill destination is a judgment call.**
Api-implementation's raw request nested the prefill map at
`context.subject` — untyped in the old wire shape. The SDK's
`StartJourneyContext.subject` is now fully typed
(`StartJourneyIdentity`/`StartJourneyDocument`/...), so an arbitrary
`Map<String,Object>` can no longer be dropped there generically.
`StartJourneyRequest` does carry a separate, genuinely untyped `data` field
alongside `context` — FINDINGS.md (Q2) reads that as "the subject-prefill
map, untyped", and `SdkInteractionMapper.toStartRequest()` follows that
reading, sending `context.config.delivery=API` (confirmed to exist and match
the raw `"api"` value exactly) with an empty typed `subject`, and `prefill`
in `data`. **Not exercised against a live journey** — if prefill silently has
no effect, this mapping is the first thing to revisit.

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

**(i) `fetchInteraction`'s "still working" response shape carries no
decision, so a same-poll short-circuit is lost.** Api-implementation's raw
`GoInteractionFetchResponse` always carried a `result` field, so a fetch that
came back `Completed` could sometimes render the decision screen directly,
saving a round trip to `/state`. The SDK's `FetchInteractionResponseBody` is
a `oneOf` of three shapes — `ResponseBody1` (a full interaction, used
whenever there's something to render) and `ResponseBody2` (`instanceId` +
`journey.status` + a `processing` flag, no `interaction`, no `result`, used
while Go has nothing to show yet) — and `ResponseBody2` never carries a
decision. `GoSdkClient`/`SdkInteractionMapper` always defer to a processing
screen in that branch and rely on the front end's separate `/state` poll
(`GoSdkClient.fetchState` → `journeys().getState()`, which *does* carry
enough to decide, per (b) above) to surface the real outcome. This is a
correctness-safe, deliberate behaviour difference from api-implementation —
never a wrong answer, just occasionally one extra poll before the customer
sees it.

**(j) Nothing in this module has been run against a live tenant.** Every
point above says so individually, but it bears restating once, plainly: the
spike read real generated source rather than guessing, which is why most of
the risk here is narrower than an ordinary alpha-SDK integration would carry
— but reading source is not the same as a response on the wire. Phase 7 of
the project plan (live-mode verification against Meridian Health's published
journey) is explicitly out of scope for the session that built this module.
