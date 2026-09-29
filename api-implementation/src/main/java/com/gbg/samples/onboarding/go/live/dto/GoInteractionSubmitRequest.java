package com.gbg.samples.onboarding.go.live.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * POST {baseUrl}journey/interaction/submit request — see
 * /docs/go-v2/api-reference/endpoint/submit-interaction.
 *
 * Go splits a submission into two parts, and they are not interchangeable:
 * {@code participants} is a *manifest* naming which domain elements this call
 * covers (a domainElementId and nothing else — it carries no values), while
 * the values themselves travel in {@code context.subject}, nested along the
 * journey's schema paths. {@code context.subject} is required by the spec.
 * Sending values inside {@code participants}, or omitting {@code context}
 * entirely, is rejected however the field names are spelled.
 */
public record GoInteractionSubmitRequest(String instanceId, String interactionId,
                                          List<Participant> participants, Context context) {

    /**
     * One entry per domain element being submitted. Deliberately id-only:
     * the spec allows extra members, but values belong in context.subject.
     */
    public record Participant(String domainElementId) {
    }

    public record Context(Map<String, Object> subject) {
    }

    /**
     * Maps the front end's flat, field-name-keyed submission onto Go's two-part
     * shape. Each entry contributes a {@link Participant} (the domain element
     * id) and a value nested at that element's schema path under
     * {@code subject}.
     *
     * The table below is the sample's own front-end field names, which were
     * invented before any journey existed. It is deliberately narrow: a real
     * deployment must replace it with the published journey's schema
     * (Dashboard → journey → Actions → View schema), because domain element
     * ids and their schema paths are per-journey. An unmapped key falls back
     * to the key itself as the domain element id and a flat
     * {@code subject.<key>} placement — wrong for a real journey, but visible
     * and debuggable rather than silently dropped.
     */
    public static GoInteractionSubmitRequest of(String instanceId, String interactionId, Map<String, Object> data,
                                                 String consentUrl) {
        if (data == null || data.isEmpty()) {
            return new GoInteractionSubmitRequest(instanceId, interactionId, List.of(), new Context(Map.of()));
        }

        List<Participant> participants = new java.util.ArrayList<>();
        Map<String, Object> subject = new LinkedHashMap<>();

        // The consent screen posts one boolean per checkbox; Go's Consent
        // Collection module takes a single consent record whose only required
        // member is a URL. Handled before the field loop so the checkbox keys
        // don't also fall through to the generic mapping.
        if (data.keySet().stream().anyMatch(CONSENT_KEYS::contains)) {
            participants.add(new Participant("Consent"));
            subject.put("consent", List.of(consentRecord(data, consentUrl)));
        }

        data.forEach((key, value) -> {
            // A null would otherwise go out as the string "null" (e.g.
            // email:"null", which Go rejects).
            if (CONSENT_KEYS.contains(key) || value == null) return;
            FieldMapping mapping = FieldMapping.forKey(key);
            participants.add(new Participant(mapping.domainElementId()));
            mapping.place(subject, value);
        });

        return new GoInteractionSubmitRequest(instanceId, interactionId, participants, new Context(subject));
    }

    /**
     * Go path → submitted field name, for the same {@code data} {@link #of}
     * would send.
     *
     * Go reports a rejected submit as one error whose {@code problem} names
     * each failing value by its path in the request, e.g.
     * {@code context.subject.identity.emails.0.email: Invalid email address}
     * (live tenant, 2026-09-29). Array indices depend on the order the fields
     * were placed, so the only reliable way back to the field the customer
     * typed into is to run the same placement and record where each one went.
     */
    public static Map<String, String> fieldsByGoPath(Map<String, Object> data) {
        Map<String, String> byPath = new LinkedHashMap<>();
        if (data == null) {
            return byPath;
        }
        Map<String, Object> subject = new LinkedHashMap<>();
        data.forEach((key, value) -> {
            if (CONSENT_KEYS.contains(key) || value == null) return;
            byPath.put(FieldMapping.forKey(key).place(subject, value), key);
        });
        return byPath;
    }

    /**
     * The consent checkboxes the Meridian screen collects. Only the first
     * gates access — a patient who will not share with clinicians cannot be
     * given a record — so it is the one the module's outcome turns on; the
     * other two ride along on the same record as preferences.
     */
    private static final List<String> CONSENT_KEYS =
            List.of("shareWithClinicians", "sharePrescriptions", "useForResearch");

    private static Map<String, Object> consentRecord(Map<String, Object> data, String consentUrl) {
        Map<String, Object> consent = new LinkedHashMap<>();
        consent.put("type", "explicit");
        consent.put("url", consentUrl);
        consent.put("terms", "I agree that Meridian Health may access and share my patient record "
                + "with clinicians treating me.");
        consent.put("effectiveDate", java.time.Instant.now().toString());
        // The two optional preferences, recorded alongside the agreement so the
        // patient's choices survive in the journey session rather than being
        // dropped at the transport boundary.
        consent.put("purpose", CONSENT_KEYS.stream()
                .filter(k -> Boolean.TRUE.equals(data.get(k)))
                .reduce((a, b) -> a + "," + b)
                .orElse(""));
        return consent;
    }

    /**
     * One front-end field name → its Go domain element id and schema path.
     *
     * Paths verified by live submission against the Meridian Health journey on
     * {@code gbggo4-demo} (2026-09-07): a Consent submission shaped this way
     * returns {@code {"status":"success"}} and clears {@code Consent/url} from
     * the next fetch's {@code outstanding}.
     *
     * Three shapes appear here, and they are not interchangeable — the docs are
     * explicit that documents and biometrics placed outside {@code subject}
     * cause the request to fail:
     *
     * <ul>
     *   <li><b>identity</b> — plain nested values under {@code subject.identity}.</li>
     *   <li><b>documents / biometrics</b> — arrays of objects directly under
     *       {@code subject}, holding base64 image data.</li>
     *   <li><b>consent</b> — an array under {@code subject}, whose only
     *       required member is {@code url}.</li>
     * </ul>
     */
    private record FieldMapping(String domainElementId, String[] path, Wrap wrap) {

        /** How a value is packaged at its leaf: bare, or as a single-entry array of an object. */
        private enum Wrap {
            NONE,
            /** {@code phones: [{type, number}]} */
            PHONE,
            /** {@code phones: [{type: "mobile", number}]} */
            PHONE_MOBILE,
            /** {@code phones: [{type: "landline", number}]} */
            PHONE_LANDLINE,
            /** {@code emails: [{type: "personal", email}]} */
            EMAIL_PERSONAL,
            /** {@code emails: [{type: "work", email}]} */
            EMAIL_WORK,
            /** {@code idNumbers: [{type, idNumber}]} */
            ID_NUMBER,
            /** {@code idNumbers: [{type: "SSN", idNumber}]} */
            SSN,
            /** {@code previousAddresses: [{addressString}]} */
            PREVIOUS_ADDRESS,
            /** {@code documents: [{type, side1Image, side2Image}]} */
            DOCUMENT_SIDE1,
            DOCUMENT_SIDE2,
            /** {@code biometrics: [{selfieImage}]} */
            SELFIE
        }

        private static final Map<String, FieldMapping> KNOWN = Map.ofEntries(
                // Identity. Absent from the current Meridian journey — Data
                // Verification was removed after failing to source FullName on
                // this tenant — but kept so restoring that module needs no
                // change here.
                Map.entry("fullName", new FieldMapping("FullName", new String[]{"identity", "firstName"}, Wrap.NONE)),
                Map.entry("firstName", new FieldMapping("FullName", new String[]{"identity", "firstName"}, Wrap.NONE)),
                Map.entry("lastNames", new FieldMapping("FullName", new String[]{"identity", "lastNames"}, Wrap.NONE)),
                Map.entry("dateOfBirth", new FieldMapping("DateOfBirth", new String[]{"identity", "dateOfBirth"}, Wrap.NONE)),

                // Address. Go wants the components, not one free-text line, and
                // which of them are required is the journey's choice: Meridian
                // and Northbank ask for building, thoroughfare, locality,
                // postalCode and country, while Ridgeline also requires
                // premise. Every component a market might mark required is
                // mapped here, because an unmapped one falls through to the
                // generic placement and Go rejects the submit — "Required
                // domain element 'CurrentAddress/premise' data is missing from
                // context" — on a screen the customer has already filled in.
                Map.entry("premise", new FieldMapping("CurrentAddress",
                        new String[]{"identity", "currentAddress", "premise"}, Wrap.NONE)),
                Map.entry("subBuilding", new FieldMapping("CurrentAddress",
                        new String[]{"identity", "currentAddress", "subBuilding"}, Wrap.NONE)),
                Map.entry("dependentThoroughfare", new FieldMapping("CurrentAddress",
                        new String[]{"identity", "currentAddress", "dependentThoroughfare"}, Wrap.NONE)),
                Map.entry("dependentLocality", new FieldMapping("CurrentAddress",
                        new String[]{"identity", "currentAddress", "dependentLocality"}, Wrap.NONE)),
                Map.entry("addressString", new FieldMapping("CurrentAddress",
                        new String[]{"identity", "currentAddress", "addressString"}, Wrap.NONE)),
                Map.entry("building", new FieldMapping("CurrentAddress",
                        new String[]{"identity", "currentAddress", "building"}, Wrap.NONE)),
                Map.entry("thoroughfare", new FieldMapping("CurrentAddress",
                        new String[]{"identity", "currentAddress", "thoroughfare"}, Wrap.NONE)),
                Map.entry("locality", new FieldMapping("CurrentAddress",
                        new String[]{"identity", "currentAddress", "locality"}, Wrap.NONE)),
                Map.entry("postcode", new FieldMapping("CurrentAddress",
                        new String[]{"identity", "currentAddress", "postalCode"}, Wrap.NONE)),
                // Go's element leaf is postalCode; "postcode" above is the short
                // name a hand-built screen sends. Both are here so a FORM stage
                // named after the ref resolves by leaf like every other field.
                Map.entry("postalCode", new FieldMapping("CurrentAddress",
                        new String[]{"identity", "currentAddress", "postalCode"}, Wrap.NONE)),
                Map.entry("country", new FieldMapping("CurrentAddress",
                        new String[]{"identity", "currentAddress", "country"}, Wrap.NONE)),

                Map.entry("mobileNumber", new FieldMapping("MobilePhone", new String[]{"identity", "phones"}, Wrap.PHONE)),

                // Contact and personal details. Phones and emails are arrays
                // of typed objects; the type discriminator is what maps each
                // entry back to its domain element (submit-interaction
                // reference: 'personal' -> PersonalEmail, 'work' -> WorkEmail).
                // Both leaves are "number"/"email", so these can only be
                // resolved by their full ref, never by leaf.
                Map.entry("MobilePhone/number",
                        new FieldMapping("MobilePhone", new String[]{"identity", "phones"}, Wrap.PHONE_MOBILE)),
                Map.entry("LandlinePhone/number",
                        new FieldMapping("LandlinePhone", new String[]{"identity", "phones"}, Wrap.PHONE_LANDLINE)),
                Map.entry("PersonalEmail/email",
                        new FieldMapping("PersonalEmail", new String[]{"identity", "emails"}, Wrap.EMAIL_PERSONAL)),
                Map.entry("WorkEmail/email",
                        new FieldMapping("WorkEmail", new String[]{"identity", "emails"}, Wrap.EMAIL_WORK)),
                Map.entry("MothersMaidenName",
                        new FieldMapping("MothersMaidenName", new String[]{"identity", "mothersMaidenName"}, Wrap.NONE)),
                // idNumbers[] again, with the type naming which identifier it
                // is — the same shape NationalInsuranceNumber uses below.
                Map.entry("SSN",
                        new FieldMapping("SSN", new String[]{"identity", "idNumbers"}, Wrap.SSN)),
                // previousAddresses[] is an array of address objects. The
                // screen collects one free-text line, which lands as that
                // entry's addressString.
                Map.entry("PreviousAddresses",
                        new FieldMapping("PreviousAddresses",
                                new String[]{"identity", "previousAddresses"}, Wrap.PREVIOUS_ADDRESS)),
                Map.entry("Gender",
                        new FieldMapping("Gender", new String[]{"identity", "gender"}, Wrap.NONE)),
                // idNumbers[] carries a type and the number itself, the same
                // shape as phones — an NI number is not a bare identity field.
                Map.entry("NationalInsuranceNumber",
                        new FieldMapping("NationalInsuranceNumber", new String[]{"identity", "idNumbers"}, Wrap.ID_NUMBER)),

                // Document and biometric capture. The front end sends an
                // attachment reference or a data URL; either way it lands as
                // base64 image data inside subject.documents / subject.biometrics.
                Map.entry("documentImage", new FieldMapping("PrimaryDocument", new String[]{"documents"}, Wrap.DOCUMENT_SIDE1)),
                Map.entry("documentBack", new FieldMapping("PrimaryDocument", new String[]{"documents"}, Wrap.DOCUMENT_SIDE2)),
                Map.entry("selfieImage", new FieldMapping("Selfie", new String[]{"biometrics"}, Wrap.SELFIE))
        );

        /**
         * The mapping for one submitted field, by either name it can arrive under.
         *
         * A FORM stage's fields are named by {@code DefaultInteractionMapper.fieldsFor}
         * after the domain element refs Go reports outstanding, so the front end
         * submits {@code CurrentAddress/postalCode} rather than {@code postcode}.
         * The keys below are the short names, which is all the capture and consent
         * screens ever send — and until Northbank became the first market with a
         * configured FORM stage, no request had exercised the difference. A
         * prefixed ref missed every entry, fell through to the fallback, and Go
         * rejected the submit with "Required domain element
         * 'CurrentAddress/building' data is missing from context", which reaches
         * the customer as a Continue button that does nothing.
         *
         * So: try the key as sent, then its leaf. Leaf names are unique across
         * this table, and matching {@code <Element>/<leaf>} on the leaf lands a
         * ref at the same path as the short name it duplicates.
         */
        static FieldMapping forKey(String key) {
            FieldMapping known = KNOWN.get(key);
            if (known == null && key.contains("/")) {
                known = KNOWN.get(key.substring(key.lastIndexOf('/') + 1));
            }
            return known != null ? known : new FieldMapping(key, new String[]{key}, Wrap.NONE);
        }

        /**
         * A country as Go will accept it: {@code /^[A-Z]{2,3}$/}.
         *
         * The front end renders this as a plain text box — its field type
         * vocabulary has no select — so a customer types "United Kingdom" and
         * Go answers 400 "Invalid string: must match pattern", which surfaces
         * as a Continue button that does nothing. An already-valid code passes
         * through untouched (upper-cased), and a name this table doesn't know
         * is sent as typed so Go's own error stands rather than a guess.
         *
         * Deliberately short: the UK plus the countries this demo's journeys
         * name. It is a nudge for hand-typed input, not an i18n country table.
         */
        private static final Map<String, String> COUNTRY_CODES = Map.ofEntries(
                Map.entry("UNITED KINGDOM", "GBR"),
                Map.entry("GREAT BRITAIN", "GBR"),
                Map.entry("ENGLAND", "GBR"),
                Map.entry("SCOTLAND", "GBR"),
                Map.entry("WALES", "GBR"),
                Map.entry("NORTHERN IRELAND", "GBR"),
                Map.entry("UK", "GBR"),
                Map.entry("IRELAND", "IRL"),
                Map.entry("UNITED STATES", "USA"),
                Map.entry("UNITED STATES OF AMERICA", "USA"),
                Map.entry("USA", "USA")
        );

        private static String countryCode(String typed) {
            String trimmed = typed == null ? "" : typed.trim();
            String upper = trimmed.toUpperCase(java.util.Locale.ROOT);
            if (upper.matches("^[A-Z]{2,3}$")) {
                return upper;
            }
            String mapped = COUNTRY_CODES.get(upper);
            return mapped != null ? mapped : trimmed;
        }

        /**
         * Places {@code value} under {@code subject} and returns the path Go
         * would name it by in a validation error, e.g.
         * {@code context.subject.identity.emails.0.email}.
         */
        @SuppressWarnings("unchecked")
        String place(Map<String, Object> subject, Object value) {
            Map<String, Object> cursor = subject;
            for (int i = 0; i < path.length - 1; i++) {
                cursor = (Map<String, Object>) cursor.computeIfAbsent(path[i], k -> new LinkedHashMap<String, Object>());
            }
            String leaf = path[path.length - 1];
            String at = "context.subject." + String.join(".", path);
            String text = String.valueOf(value);
            return switch (wrap) {
                case PHONE -> {
                    cursor.put(leaf, List.of(Map.of("type", "mobile", "number", text)));
                    yield at + ".0.number";
                }
                // Appended, not put: a journey collecting both a personal and a
                // work email sends them as separate fields on one screen, and
                // the second would otherwise replace the first in emails[].
                case PHONE_MOBILE -> at + "." + append(cursor, leaf, Map.of("type", "mobile", "number", text)) + ".number";
                case PHONE_LANDLINE -> at + "." + append(cursor, leaf, Map.of("type", "landline", "number", text)) + ".number";
                case EMAIL_PERSONAL -> at + "." + append(cursor, leaf, Map.of("type", "personal", "email", text)) + ".email";
                case EMAIL_WORK -> at + "." + append(cursor, leaf, Map.of("type", "work", "email", text)) + ".email";
                case ID_NUMBER -> at + "." + append(cursor, leaf,
                        Map.of("type", "NationalInsuranceNumber", "idNumber", text)) + ".idNumber";
                case SSN -> at + "." + append(cursor, leaf, Map.of("type", "SSN", "idNumber", text)) + ".idNumber";
                case PREVIOUS_ADDRESS -> at + "." + append(cursor, leaf, Map.of("addressString", text)) + ".addressString";
                // Both document sides belong to one entry in documents[], so a
                // second side merges into the existing object rather than
                // appending a second document.
                case DOCUMENT_SIDE1 -> {
                    mergeDocument(cursor, leaf, "side1Image", text);
                    yield at + ".0.side1Image";
                }
                case DOCUMENT_SIDE2 -> {
                    mergeDocument(cursor, leaf, "side2Image", text);
                    yield at + ".0.side2Image";
                }
                // `type` is required alongside the image: the Liveness
                // Verification module's own reference lists it as such, and
                // without it Go accepts the submission but leaves
                // Selfie/selfieImage outstanding — a silent no-op rather than
                // an error. The general submit example in the API docs omits
                // it, which is what makes this one easy to miss.
                case SELFIE -> {
                    cursor.put(leaf, List.of(new LinkedHashMap<>(Map.of("type", "Selfie", "selfieImage", text))));
                    yield at + ".0.selfieImage";
                }
                case NONE -> {
                    cursor.put(leaf, "country".equals(leaf) ? countryCode(text) : value);
                    yield at;
                }
            };
        }

        /** Adds one entry to a typed array under {@code leaf}, creating it if absent; returns its index. */
        @SuppressWarnings("unchecked")
        private static int append(Map<String, Object> cursor, String leaf, Map<String, String> entry) {
            Object existing = cursor.get(leaf);
            List<Object> entries = existing instanceof List<?> list
                    ? (List<Object>) list
                    : new java.util.ArrayList<>();
            if (!(existing instanceof List<?>)) {
                cursor.put(leaf, entries);
            }
            entries.add(new LinkedHashMap<>(entry));
            return entries.size() - 1;
        }

        /**
         * Both sides of one document belong to a single {@code documents[]}
         * entry, so a second side merges into the existing object rather than
         * appending a second document.
         *
         * An absent side is <em>omitted</em>, never sent as an empty string:
         * Go validates a supplied image as a non-empty string and rejects the
         * whole submission with
         * {@code documents.0.side2Image: Too small: expected string to have >=1 characters}.
         * The API docs show {@code "side2Image": ""} for a single-sided
         * document, but the live platform does not accept it.
         */
        @SuppressWarnings("unchecked")
        private static void mergeDocument(Map<String, Object> cursor, String leaf, String side, String image) {
            List<Map<String, Object>> documents = (List<Map<String, Object>>) cursor.get(leaf);
            Map<String, Object> document;
            if (documents == null || documents.isEmpty()) {
                document = new LinkedHashMap<>();
                document.put("type", "Primary");
                cursor.put(leaf, new java.util.ArrayList<>(List.of(document)));
            } else {
                document = documents.get(0);
            }
            document.put(side, image);
        }
    }
}
