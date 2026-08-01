# PointAccount 지원 조회 vertical slice를 만든다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `false`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/active/customer-order-cancellation-11-benefit-policy-and-operator-grant-foundation.md`, `docs/exec-plans/active/signed-cursor-foundation.md`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

고객은 자기 point account/ledger를, explicit `POINT_ACCOUNT_READ` grant를 가진 Platform Operator는
reason과 Audit을 남기는 지원 조회만 수행하게 만든다.

## Current State

- OpenAPI GET 계약은 있으나 Loyalty controller/query owner와 cursor contract 구현이 없다.
- operator support read의 permission, reason, audit commit gate가 없다.

## Definitions

- **Support read:** operator grant, `X-Access-Reason`, target Audit이 모두 commit된 조회.

## Scope

### In Scope

- `POINT_ACCOUNT_READ` vocabulary migration/enforcement
- customer ownership 및 operator support read Application Service/DTO projection
- `(occurredAt DESC, transactionId DESC)` signed, account-bound cursor

### Non-goals

- policy/grant base schema, point adjustment, refund/recovery business write, issuer/evidence exposure

## Business Rules and Invariants

- customer는 own account만 reason 없이 읽는다.
- operator branch는 active grant/reason/Audit 없이는 body를 반환하지 않는다.
- issuer reference, evidence, key, internal case/grant state는 response에 넣지 않는다.

## Architecture and Transaction Boundaries

Loyalty Query Application Service가 ownership 또는 Operations authorization boundary를 호출한다.
operator audit과 projection decision은 한 local transaction에 있고 Controller는 Repository를 직접 호출하지 않는다.

## Alternatives Considered

- Plan 11에 query까지 포함: authorization foundation과 customer read contract를 불필요하게 결합해 제외한다.

## Failure Semantics

grant/audit/projection failure는 503이며 empty/stale/role-only response로 대체하지 않는다.

## Data and Migration

generic grant schema와 `POINT_ACCOUNT_READ`를 포함한 네 값의 closed vocabulary는 Plan 11을
소비한다. 이 plan은 grant/vocabulary migration을 만들거나 constraint를 확장하지 않는다.
account ledger write tables는 변경하지 않는다.

## API and Event Contracts

`GET /point-accounts/{accountId}`와 `/transactions`를 OpenAPI와 일치시킨다. customer/operator header
branch, 400/403/503, cursor scope/limit contract를 포함한다.

## Milestones

1. Plan 11의 `POINT_ACCOUNT_READ` permission enforcement와 ownership/query projection을 구현한다.
2. reason/audit commit gate와 cursor codec integration을 구현한다.
3. API/security/failure contract tests를 완료한다.

## Required Tests

- own/other customer, role+grant/revoked operator
- missing/blank reason, audit/projection failure 503
- cursor ordering, account mismatch, data-sensitivity non-exposure

## Validation Commands

```bash
./gradlew test --tests '*PointAccount*' --tests '*PointTransaction*'
./gradlew test --tests '*ModularityTests'
./gradlew clean build
bash scripts/verify-docs.sh
git diff --check
```

## Observability

point-account read metric uses only actor type/outcome tags.

## Documentation Updates

ADR-069/070, OpenAPI, authorization matrix and decision closure를 갱신한다.

## Progress

- [ ] permission vocabulary
- [ ] ownership/query projection
- [ ] audit/cursor contract
- [ ] validation evidence

## Surprises & Discoveries

- 없음.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-08-01 | Accepted | support read를 독립 vertical slice로 분리 | policy authorization과 customer data projection을 분리 | ADR-069, ADR-070 |

## Outcomes & Retrospective

미구현 상태다. Plan 11과 signed-cursor outcome 없이는 시작하지 않는다.

## Revision Notes

- 2026-08-01: 기존 Plan 10의 point-account read scope를 분리했다.
