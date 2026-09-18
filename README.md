# go-sample-backend-java

Two independent implementations of the same onboarding thin-proxy service
(see `HANDOFF.md` for what this app is and how it fits with the front end).
Both implement the identical REST contract — a front end can't tell which one
it's talking to — and both run standalone, on their own ports, against the
same GBG Go v2 journeys.

- **[`api-implementation/`](api-implementation/README.md)** — integrates with
  GBG Go v2 via hand-rolled HTTP calls (`RestClient`, hand-written request/
  response DTOs). The original, proven implementation.
- **[`sdk-implementation/`](sdk-implementation/README.md)** — integrates via
  GBG's Java Core SDK (`com.gbg:go-core-sdk`), currently alpha. Built to
  compare against `api-implementation` and surface gaps in the SDK.

`docs/northbank-journey-build-spec.md` is shared reference material — the Go
Journey Builder spec both implementations target — and isn't specific to
either one.

Each implementation has its own README with its own architecture, running
instructions, and known gaps.
