# Support Protected Exact Search Policy

> **Status:** Accepted initial policy (2026-08-11)
> **Legal review required before production.**

## Scope and owner profiles

S30 supports exact phone/email search for three typed subjects. Each owner Context stores its own encrypted raw values,
masked derivatives and versioned blind indexes; Support stores no profile copy.

| Subject type | Owner Context | Minimum profile fields |
|---|---|---|
| `CUSTOMER` | Identity | customer ID, display name, primary phone, primary email |
| `STORE` | Merchant | store ID, legal display name, support phone, support email |
| `RIDER` | Delivery | internal external-courier ID, provider code, encrypted provider courier reference, display name, relay phone, relay email |

The Rider row is an external-courier reference, not a first-party rider Aggregate or workforce model. Every PII field is
optional individually, but a profile must have a masked display label and at least one searchable phone or email. PAN,
CVC, password, OTP, MFA secret, access/refresh/payment token, encryption key and resident-registration number have no
column, command or response field in this model.

Profile creation/change/recovery workflows, raw reveal, authentication recovery, payout/tax ownership and first-party
rider employment data are outside S30. Owner-local persistence/query boundaries are still implemented so later owner
workflows do not write Support tables.

## Normalization

Normalization is version `v1` and runs only in memory before the raw request value is discarded.

- Phone applies Unicode NFKC, removes ASCII/Unicode whitespace and `-`, `.`, `(`, `)`, converts a `00` international
  prefix to `+`, and accepts 8–15 E.164 digits. A Korean domestic number beginning with `0` is converted to `+82` after
  removing that zero. Any other prefix, alphabetic/control character or invalid length is rejected.
- Email applies Unicode NFKC, trims outer whitespace, rejects control/inner whitespace, requires exactly one `@`,
  lowercases with locale-independent rules and converts the domain through IDNA ASCII. Local part, domain and total
  length are bounded by 64, 253 and 320 characters. Empty labels and invalid IDNA are rejected.
- Blind-index input includes normalization version and criterion type. A normalized phone can never match an email even
  if their bytes happen to be equal.

Invalid input returns a generic 400 without echoing any fragment of the submitted value.

## Masking and bounded response

Search success always returns masked owner DTOs, including a zero-result response. Mask format version is `v1`.

- Phone exposes only the final four digits as `***-****-1234`.
- Email exposes the first local-part character, a fixed `***`, the first domain-label character, a fixed `***`, and the
  public suffix when present, for example `d***@e***.com`. One-character components are replaced with `*`.
- Display labels expose the first and last Unicode code point when length is at least three and replace every interior
  code point with `*`; one character becomes `*`, and two characters expose only the first followed by `*`.

A request selects one or more of `CUSTOMER`, `STORE`, `RIDER`, uses one criterion and one reason code, and returns at
most 20 candidates in `(subjectType, subjectId)` order. It includes total matched count capped at 21, `ambiguous=true`
when more than one subject matched, and `hasMore=true` when the bounded query observed more than 20. No cursor, raw
criterion, normalized value, ciphertext or blind index is returned.

Allowed reason codes are `CASE_INTAKE`, `ACTIVE_CASE_LOOKUP`, `DELIVERY_INCIDENT` and `PRIVACY_REQUEST`. Free-form reason
text is not accepted. `CASE_INTAKE` permits search before a Case exists; the other reasons do not manufacture Case or
verification state.

## Authorization, rate control and Audit

Every request requires the persistent `SUPPORT_SUBJECT_SEARCH` permission. Each actor may start at most 30 accepted
search attempts per fixed five-minute window. PostgreSQL derives the window start, current time and `Retry-After` from
one database clock in the same atomic upsert, so application-node clock skew cannot split a quota. The counter is
persistent, PII-free and consumed before the Vault call; retries are new read attempts because this endpoint does not
mutate owner state. Exceeding the limit returns 429 with `Retry-After`.

Fixed-window semantics can permit up to two window quotas around a real boundary; this boundary burst is an explicit
initial-policy limitation, not a sliding-window claim. The 30/5-minute value must be revisited with measured legitimate
operator and abuse traffic before production. Enforcement rows are transient state retained for 24 hours; the two-year
`PII_ACCESS` Audit is the canonical access record. A scheduled worker deletes at most 100 expired rows per short
transaction by default, uses `(window_started_at, actor_id)` order with `SKIP LOCKED`, and can be safely rerun. Deleted
count, remaining backlog, oldest-retained age and failures are PII-free metrics. Cleanup failure never converts a search
failure or success and is retried independently.

After Vault HMAC generation and before a response is returned, the final local transaction revalidates the persistent
permission, queries owner public APIs and commits a `PII_ACCESS` search Audit. Audit contains only a generated search ID,
actor ID, criterion type, selected subject types, reason code, bounded result count, ambiguity and truncation flags. It
never contains raw/normalized criteria, masked values, profile labels, subject IDs, ciphertext, blind indexes, key names
or Vault response data. Audit failure returns 503 and no search response.

The supported contract accepts raw criteria only in the POST JSON body and rejects every query parameter. A client can
still place a value in the URI before BeanFlow rejects it, so production ingress and container access logs for this
route must record path only or redact the query string. BeanFlow never places the criterion in its application log,
trace, metric label, cursor, Audit, exception message, snapshot or Support persistence. Sensitive success responses use
`Cache-Control: no-store`.

## Failure semantics

- malformed criterion/subject/reason: 400, generic message;
- missing/revoked permission: 403;
- actor rate limit: 429 with bounded `Retry-After`;
- Vault Proxy/key/timeout/response, owner query, database or Audit failure: 503;
- no match: masked empty 200 only when all required dependencies and Audit succeeded;
- no local/plaintext scan, in-memory key, cached/stale result, mock/no-op provider or partial-owner empty fallback.

## Revisit conditions

- measured operator traffic requires a different rate or result bound;
- another country numbering plan or email canonicalization policy is required;
- a first-party Rider Aggregate replaces the external courier reference;
- fuzzy/prefix/address/order search is requested;
- masking rules or legal classification changes.
