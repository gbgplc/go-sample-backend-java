# Engineering handoff — Market Onboarding Journeys

For an engineer picking this up cold. Covers both repos: what they are, how to
run them, and exactly where things stand on connecting to the real GBG Go
platform. Written 2026-09-01.

This same file is duplicated at the root of both repos (`front-end-sample`
and `back-end-sample-java`) so it's found regardless of which one you clone
first. If you edit one, edit both.

## 1. What this is

Three standalone customer-facing onboarding apps — **Northbank** (banking),
**Meridian Health** (healthcare), **Ridgeline Play** (online gaming) — each a
Next.js front end talking over REST to its own Java Spring Boot thin-proxy
backend, which in turn is meant to drive a GBG Go journey. Every app and its
backend run with **no live Go connection required** by default (mock mode) —
that's deliberate, not a shortcut: it's what let this whole thing get built
and demoed before any real journey existed.

Origin: a Claude Design mockup (`Market Onboarding Journeys.dc.html`) turned
into this. See git history in both repos for the full build sequence — it's
one commit per meaningful change with a descriptive message, genuinely worth
reading if you want the "why" behind a decision.

## 2. Repo layout

```
front-end-sample/                  npm workspace monorepo
├── packages/design-system/         Button, TextField, etc. — ported from the GBG Go Platform UI bundle
├── packages/onboarding-core/       types, RestTransport, MockTransport, the session state machine
├── packages/onboarding-ui/         the 8 screen kinds, the responsive shell, OnboardingApp
└── apps/{northbank,meridian-health,ridgeline-play}/   one Next.js app each, own brand config + mock fixtures

back-end-sample-java/               Spring Boot, Maven, one deployable
├── src/main/java/.../api/          SessionController (7 REST endpoints) + error handling
├── src/main/java/.../go/mock/      MockGoClient + per-market fixture scripts (mirrors the TS mock)
├── src/main/java/.../go/live/      GoApiClient + GoTokenService — real GBG Go v2 integration
└── docs/northbank-journey-build-spec.md   what to build in the Go Journey Builder for the pilot
```

One backend deployment fronts exactly one market — a Spring profile
(`northbank` / `meridian-health` / `ridgeline-play`) sets the brand config,
resource ID, and port, one-to-one with the three front-end apps.

## 3. Prerequisites

- Node 18+ and npm
- Java 21+ and Maven
- Internet access to the npm registry and Maven Central for first install
  (no VPN or special access needed for any of that)
- **Nothing else** for mock mode — no Go account, no credentials, no journey.

## 4. Running everything (mock mode — the default)

Front end:
```
cd front-end-sample
npm install
npm run dev:northbank         # :3000
npm run dev:meridian-health   # :3001
npm run dev:ridgeline-play    # :3002
```

Backend, one process per market:
```
cd back-end-sample-java
mvn spring-boot:run -Dspring-boot.run.profiles=northbank        # :8081
mvn spring-boot:run -Dspring-boot.run.profiles=meridian-health  # :8082
mvn spring-boot:run -Dspring-boot.run.profiles=ridgeline-play   # :8083
```

Each front-end app is fully usable **on its own**, with no backend running at
all — it drives its own in-browser mock (`MockTransport`). You only need the
Java backend up if you specifically want to exercise the real REST hop (see
section 5) or look at the API docs.

Swagger UI per backend instance: `http://localhost:808X/swagger-ui/index.html`
(raw spec at `/v3/api-docs`).

As of this writing all six processes are up on the ports above.

## 5. Wiring a front-end app to its Java backend

Still mock-mode Go underneath, but this makes the front end hit the real
Spring Boot REST API instead of its own in-browser mock — the actual
integration seam, not the demo shortcut.

Create `apps/<market>/.env.local`:
```
NEXT_PUBLIC_ONBOARDING_TRANSPORT=rest
NEXT_PUBLIC_API_BASE_URL=http://localhost:8081
```
Restart that app's dev server. CORS is already configured for the matching
port pair (`app.cors-allowed-origins` in each `application-<market>.yml`).
Remember to delete `.env.local` (or flip the var back) when you're done —
it's gitignored, so it won't get committed, but it will silently change the
app's behaviour for anyone running it on that machine until removed.

## 6. Reaching every designed outcome

`?mock_scenario=<id>` on the front-end URL (it's automatically forwarded to
the Java backend too, so this works the same whether the app is talking to
its own mock or to the backend's mock):

| App | Scenarios |
|---|---|
| Northbank | `straight` (default) · `refer` · `denied` |
| Meridian Health | `self` (default) · `carer` · `carer-denied` |
| Ridgeline Play | `instant` (default) · `stepup` · `blocked` |

Example: `http://localhost:3000/?mock_scenario=denied`

## 7. Connecting to the real GBG Go platform — current state

This is the part most likely to need picking up. Read this section in full
before touching `go.mode`.

**The toggle**: `go.mode` in `application.yml` (default `mock`). Setting it
to `live` switches `GoClient` from `MockGoClient` to `GoApiClient` — nothing
else in the app changes, by design.

**What `GoApiClient`/`GoTokenService` currently implement** — built against
GBG's *public* documented v2 API:
- Auth: `client_credentials` grant against `https://api.auth.gbgplc.com/as/token.oauth2`
- API base: `https://{region}.platform.go.gbgplc.com/v2/captain/` (`go.region`: eu/us/au)
- This path has **not been proven against a real tenant** — no credentials or
  published journey existed while it was being built. Structurally correct
  against the docs, nothing more claimed.

**A separate nonprod/demo environment was tested this session** — and its
auth genuinely works, but it doesn't match the above:
- Token URL: `https://gbggo4-demo.nonprod.fabric.gbgplatforms.com/auth/realms/go/protocol/openid-connect/token`
  (a Keycloak realm, not `api.auth.gbgplc.com`)
- Grant type: **`password`**, not `client_credentials` — needs `client_id`,
  `client_secret`, `username`, and `password` all four
- Confirmed working combination: `client_id` = `flow-api-test`, `username` =
  `flow-api-test` (same value, both fields), plus a client secret and
  password — **see your secrets manager / whoever shared these with you, not
  this file** (they were shared over chat during testing and should be
  treated as exposed — rotate if that channel isn't tightly controlled).
- **`GoTokenService` does not support the `password` grant yet.** Using this
  environment for real needs a code change first: a configurable grant type,
  and `username`/`password` fields on `GoProperties`.
- **The actual Go REST API base URL for this `fabric` tenant is still
  unknown.** The token endpoint being on a different host than the public
  docs strongly implies the API (`journey/start` etc.) is too — nobody has
  confirmed where yet. Don't guess at this and start firing requests at
  invented endpoints; get the real base URL first (whoever administers this
  `gbggo4-demo` tenant should have it).

**Resource ID**: still a placeholder. `application-northbank.yml`:
```yaml
app:
  resource-id: jny_uk_retail_cdd@latest   # <- not real, replace once a journey is published
```

**The journey itself doesn't exist yet.** `docs/northbank-journey-build-spec.md`
is the full spec for what to build in the Go Journey Builder for the
Northbank pilot — module chain, interaction groups, evaluation logic,
publishing checklist. Two module names in the original design mockup
("Financial Screening", "Proof of Address Extraction") turned out not to be
real GBG modules — that doc corrects them against the real module catalogue.
Meridian Health and Ridgeline Play follow once Northbank is proven out.

**Once a journey is published**, two things are needed back from whoever
builds it:
1. The `resourceId` → goes in `application-<market>.yml`.
2. The published journey's **schema** (Dashboard → journey → Actions → View
   schema) → needed to replace `DefaultInteractionMapper`'s generic
   placeholder copy ("A few more details", generic field labels) with real
   screen content mapped from the actual domain elements. Go doesn't hand
   back rendered UI copy — that mapping is this codebase's job, and it can't
   be built correctly without seeing a real journey's schema to map against.

## 8. Known gaps — don't rediscover these, they're already flagged

- **No screen for post-review outcomes on Ridgeline Play.** Northbank and
  Meridian Health both have a "reviewer denied" terminal screen; Ridgeline
  Play has no manual-review flow at all in the current design (its only
  decline is the automated jurisdiction block). Not built — was explicitly
  left for a product decision, not an oversight.
- **Document/selfie capture is a real camera surface, not a production
  capture SDK.** It genuinely opens the camera and captures a photo
  (`getUserMedia` + shutter, file-picker fallback) — GBG doesn't ship an
  installable capture component for a fully-custom-UI integration like this
  one, so this is the documented DIY pattern, not a placeholder graphic. But
  there's no document-authenticity check, glare/blur detection, or liveness
  — that's server-side, once real Go modules are live.
- **Attachment upload synthesises a reference.** `POST /v1/sessions/{id}/attachments`
  accepts the file and returns a fake reference rather than proxying it
  anywhere real — matches the placeholder status of capture generally.
- **Session storage is in-memory, single-node.** Fine for local dev and
  demos; swap `SessionStore` for Redis or similar before running more than
  one instance of the Java service.
- **The verification record shown on the final screen is a design proposal**,
  not a real payload — which fields a customer should actually see
  (especially the deciding module on a referral or decline) is a compliance
  decision, not yet made.

## 9. Where things are already documented — check here before asking

- `front-end-sample/README.md` — architecture, running, mock scenario list
- `back-end-sample-java/README.md` — architecture, API docs, session-cookie
  auth (and a real bug that was found and fixed there — worth reading if
  you're touching auth), what's a placeholder
- `back-end-sample-java/docs/northbank-journey-build-spec.md` — the Go
  journey spec, see section 7 above
- Git log in both repos — genuinely read it; commit messages explain *why*,
  not just what, including a couple of real bugs found during testing
  (a branching bug in the Meridian Health mock, a `Secure`-cookie bug that
  broke non-browser HTTP clients) and how they were found and fixed.

## 10. Handling credentials — please actually follow this

- **Never commit** a client ID, secret, username, or password to either
  repo, in any file, including config, tests, or scratch scripts. Both
  `application.yml` files are wired to read Go credentials from environment
  variables (`GBG_CLIENT_ID`, `GBG_CLIENT_SECRET`) — extend that pattern
  rather than hardcoding, if the `password` grant fields get added.
- If you're picking this up and need the nonprod demo credentials, ask
  whoever owns the `gbggo4-demo` tenant directly — they are deliberately not
  written down in this file or anywhere in either repo.
