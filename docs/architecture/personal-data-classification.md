# Support Personal-Data Classification

> **Status:** R0-R4 classification is Accepted in ADR-087. Field mapping is DRAFT; crypto/index mechanics are Proposed
> in ADR-083 and are not an approved implementation contract.
> Legal review required before production.

| Class | Examples | Default behavior |
|---|---|---|
| R0 system fact | IDs, ledger totals, settled facts, audit/approval/policy | no direct edit |
| R1 low-risk profile | display name, notification preference, public store text | masked where personal; BASIC change candidate |
| R2 sensitive profile | legal-name typo, secondary contact, ordinary address | ENHANCED and specialist |
| R3 identity/financial ownership | login/recovery channels, owner/representative, payout/tax | sequential cross-functional approval |
| R4 secret | password, OTP, token, PAN/CVC, key | never reveal or directly edit |

Additional field scopes include PHONE_NUMBER, EMAIL, DELIVERY_ADDRESS, CUSTOMER_DELIVERY_CONTACT, RIDER_RELAY_CONTACT, CURRENT_COURIER_LOCATION, PROOF_OF_DELIVERY and DELIVERY_INSTRUCTIONS. Current location is limited to safety/misdelivery/unreachable/emergency.

Owner Context must protect necessary raw PII and expose only grant-controlled reveal operations. Support stores
identifiers and masked data, not long-lived plaintext copies. If ADR-083's encrypted value/keyed-index approach is
accepted, key-version and fail-startup behavior become required; until then KMS, index, rotation and outage behavior are
open implementation decisions.
