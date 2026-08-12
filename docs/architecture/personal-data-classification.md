# Support Personal-Data Classification

> **Status:** R0-R4 classification and S100 initial field mapping are Accepted in ADR-087/SP-22; Vault Transit
> crypto/index mechanics are Accepted in ADR-083.
> Legal review required before production.

| Class | Examples | Default behavior |
|---|---|---|
| R0 system fact | IDs, profile version, ledger/settlement, audit/approval/policy | no direct edit |
| R1 low-risk profile | customer/courier display name, public store display/phone/text/pickup instructions | masked where personal; BASIC + R1 permission |
| R2 sensitive profile | customer verified legal-name typo, store operations contact, courier relay contact | ENHANCED + specialist R2 permission |
| R3 identity/financial ownership | customer login/recovery phone, store representative/opaque settlement reference, courier provider identity/opaque payout reference | ENHANCED + Support Manager then Operations exact-revision approval |
| R4 secret | password, OTP, MFA secret, token, PAN/CVC, key | never reveal or directly edit; reset/re-registration intent only |

Additional field scopes include PHONE_NUMBER, EMAIL, DELIVERY_ADDRESS, CUSTOMER_DELIVERY_CONTACT, RIDER_RELAY_CONTACT, CURRENT_COURIER_LOCATION, PROOF_OF_DELIVERY and DELIVERY_INSTRUCTIONS. Current location is limited to safety/misdelivery/unreachable/emergency.

Owner Context must protect necessary raw PII and expose only grant-controlled reveal operations. Support stores
identifiers and masked data, not long-lived plaintext copies. ADR-083 requires distinct Vault Transit encryption and
HMAC keyrings, explicit ciphertext/index key-version metadata, owner-local rotation/backfill and production fail-startup
when Proxy/key configuration or metadata validation fails. Runtime Vault failure is 503 without plaintext, local,
cached, stale, no-op or empty-result fallback.
