# Northbank — journey build spec (pilot)

For building the Northbank current-account-opening journey in the GBG Go
Journey Builder, dev/staging environment. Northbank is the pilot market —
Meridian Health and Ridgeline Play follow the same process once this is
proven out.

Every module named below is checked against the real GBG Go v2 module
catalogue (`/docs/go-v2/platform/modules/`), not carried over blind from the
design mockup. Two of the mockup's module names turned out not to be real —
see **Corrections** before you start building, so you're not hunting for a
module that doesn't exist.

## 1. Overview

| Setting | Value |
|---|---|
| Journey | UK retail current account opening |
| Delivery mode | `api` — this is a fully custom UI integration (our Next.js app + Java proxy), not a GBG-hosted page. Set `context.config.delivery: "api"` when starting. |
| Prefill | Non-prefill for the pilot — start with an empty `subject`, collect everything through the journey. Revisit later if Northbank ends up holding verified customer data upfront (front-end handoff, open decision: prefill vs non-prefill). |
| Environment | Dev / staging |
| Two outcomes designed | Straight-through (approve) and referred (address mismatch → proof of address → manual review) |

Once published, you'll get a `resourceId` in the format `{hash}@{version}`
(or `{hash}@latest`). Give it to me and I'll drop it into
`application-northbank.yml` (`app.resource-id`) and we'll flip `go.mode` to
`live`.

## 2. Corrections vs. the design mockup

The original mockup (`Market Onboarding Journeys.dc.html`) invented two
module names that read plausibly but aren't in the real catalogue:

| Mockup said | Reality | What to build instead |
|---|---|---|
| **Financial Screening** | Doesn't exist as a separate module. **PEPs and Sanctions** already covers AML/CTF financial-crime screening — that's almost certainly what "Financial Screening" was standing in for. | Run **PEPs and Sanctions** once. Don't add a second screening module. (A real module called **Financial Vulnerability** exists, but it's an affordability/vulnerability assessment — a different purpose entirely. Skip it for this pilot unless you specifically want that check.) |
| **Proof of Address Extraction** | Doesn't exist as a named module. | Build the referral branch from three real modules instead: **Document Attachments** (collects the address document) → **Document Extraction** (reads its fields) → **Address Verification** (confirms/standardises the address against what the customer typed in Step 1). |

## 3. Domain elements and interaction groups

Interaction groups are what produce our screens — each row below is a
domain element the customer supplies, grouped into the screen that collects it.

| Screen (our UI) | Domain element(s) | Go path |
|---|---|---|
| Details | Full name | `identity.firstName` + `identity.lastNames[]` |
| Details | Date of birth | `identity.dateOfBirth` |
| Details | Home address | `identity.currentAddress` |
| Details | Mobile number | `identity.phones[]` (type: mobile) |
| Document | Primary document (passport / UK driving licence / national ID) | `subject.documents[]` |
| Biometrics | Selfie | biometric capture, feeds Liveness + Facematch |
| Proof of address *(referral branch only)* | Address document (bank statement / utility bill / council tax letter) | via **Document Attachments** module, input type `AddressDocument` |

Keep these four groups as four separate interaction groups in the builder —
that's what keeps them as four distinct screens rather than one long form,
matching the design.

## 4. Module chain — straight-through path

In order:

1. **Data Verification** (UK variant) — consumes full name, DOB, address,
   mobile. Cross-references consumer/government data sources. This is the
   module whose address-match capability drives the refer branch below.
2. **Document Classification** — identifies document type from the
   uploaded image (passport / driving licence / national ID).
3. **Document Authentication** — forensic genuineness check. Recommend
   **medium sensitivity** for the pilot (balances fraud detection against
   how many test submissions get flagged) — tighten before production.
4. **Document Extraction** — reads name, DOB, document number, expiry from
   the classified document.
5. **NFC Chip Authentication** — only fires for documents with a chip
   (UK passports). Configure as conditional on Document Classification
   detecting a chip-bearing document type.
6. **Liveness Verification** — confirm a real person is present in the
   selfie. V2 (numeric 0–100 score) is fine for the pilot.
7. **Facematch Verification** — compares the selfie against the document
   portrait. V2 variant (configurable threshold) rather than NIST, so you
   can tune it during testing. Default 50-point pass threshold is a
   reasonable starting point.
8. **PEPs and Sanctions** — screen against PEP/sanctions/adverse media
   lists. Use a **Standard** variant, not a UK Instance one (those are
   being phased out per the docs).
9. **GBG Trust** — consortium fraud check (application fraud, identity
   takeover, mule activity) on the submitted identity data.

## 5. Evaluation node — straight-through vs. referral

After steps 1–9, one evaluation node decides the branch:

- **Approve** when: Data Verification's address-match capability is
  satisfied (name + address confirmed by at least one source), Document
  Authentication returns any `_PASS` result, Facematch = Match, PEPs and
  Sanctions = `no_hit`, GBG Trust score above your chosen threshold.
- **Refer** when: everything else passes but Data Verification's
  **address-specific** match fails (identity confirmed by name/DOB, but no
  source confirmed the address) — this is exactly "Your address did not
  match our data sources" from the design copy. Route to the proof-of-address
  branch below.

Nothing in the current design routes to an outright decline for banking —
only approve and refer are built. If you want a decline path (e.g. a PEPs
hit, or a Document Authentication `FAILED`), that's a new branch, not
something to force into the existing two.

## 6. Module chain — referral branch (address mismatch)

10. **Document Attachments** — collects the address document
    (`AddressDocument` input type).
11. **Document Extraction** — reads the name/address fields off it.
12. **Address Verification** — confirms/standardises the extracted address
    and compares it against what the customer entered in step 1.
13. **Manual Review** (2-button variant) — pauses the journey for a human
    Accept/Deny decision, matching "An analyst is checking your document" /
    "With our team" in the design.

**Known gap, not this pilot's problem to solve yet:** our current front-end
only has a screen for "review is pending" — there's no screen yet for what
happens after a reviewer clicks Accept or Deny in Investigate. That's a real
follow-up once this pilot is working, not something to design around now.

## 7. Field-name mismatch you'll hit going live

Our mock front-end invented its own field names for the Details form —
`fullName`, `dateOfBirth`, `homeAddress`, `mobileNumber` — because there was
no real journey to match against yet. Those don't line up with Go's real
domain element ids (`firstName`/`lastNames`, `dateOfBirth`,
`currentAddress`, `phones`). Right now the Java service's live client passes
whatever field names the front end sends straight through as
`domainElementId` values (see `GoInteractionSubmitRequest` and
`DefaultInteractionMapper`'s Javadoc) — so submissions won't validate
correctly the moment we flip to `go.mode=live`, until I rebuild that mapping
against your published journey's actual schema. Once you've published this
journey, pull its schema (Dashboard → journey → Actions → **View schema**)
and send it over — that's what I need to fix the mapping and get real screen
copy instead of the generic placeholder text.

## 8. Publishing checklist

1. Journey Builder → new journey → name it "UK retail account opening" (or
   your preferred internal name).
2. Set delivery mode to `api` in the journey's start config.
3. Build the four interaction groups from section 3.
4. Add the nine straight-through modules in order (section 4), wiring each
   module's required inputs to the domain elements above.
5. Add the evaluation node from section 5.
6. Add the four referral-branch modules (section 6) and their Manual Review
   node.
7. Publish to the dev/staging environment.
8. Send me: the `resourceId`, and (separately, once available) the
   published journey's schema for the field-mapping work in section 7.
