# 만료 혜택 정책과 operator grant foundation을 만든다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/completed/customer-order-cancellation-00-contract-baseline.md`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

다섯 immutable expired-benefit policy head와 explicit operator grant를 Operations owner 안에서
구축한다. 부분 환불과 주문 종료는 version snapshot을, privileged API는 grant/audit commit gate를
소비한다.

## Current State

- policy는 현재 role-only access이며 `(PARTIAL_REFUND, POINTS)` head가 없다.
- persistent OperatorPermissionGrant, audited bootstrap과 policy read reason contract가 없다.

## Definitions

- **Policy head:** trigger×benefit별 최신 immutable version을 CAS로 가리키는 Operations state.
- **Operator grant:** role/JWT claim fallback이 아닌 actor+permission explicit authorization fact.

## Scope

### In Scope

- five policy head/version schema, seed, GET/PATCH authorization/audit
- OperatorPermissionGrant, offline bootstrap, grant/revoke/regrant lifecycle
- 네 값의 closed permission DB vocabulary와 `EXPIRED_BENEFIT_POLICY_READ`/
  `EXPIRED_BENEFIT_POLICY_WRITE` enforcement
- OIDC workload identity 검증과 fail-closed offline bootstrap command contract

### Non-goals

- partial-refund allocation/restoration, point-account read, point adjustment command

## Business Rules and Invariants

- 허용 key는 종료용 네 key와 `PARTIAL_REFUND×POINTS`뿐이다.
- grant/Audit 저장 실패는 role-only success로 대체하지 않는다.
- default grant/direct SQL seed는 금지한다.
- workload identity issuer/audience/subject/token file 검증 실패는 bootstrap transaction을
  시작하지 않으며 static secret·application JWT·role fallback을 사용하지 않는다.

## Architecture and Transaction Boundaries

Operations Application Service가 active grant lock, policy read/PATCH, Audit을 같은 local transaction에서
조정한다. bootstrap도 grant state/version과 Audit을 하나의 transaction에 저장한다.

## Alternatives Considered

- Plan 12가 policy migration도 소유: partial-refund business flow와 Operations authorization을
  다시 결합하므로 제외한다.

## Failure Semantics

head/version/grant/Audit 조회 또는 저장 실패는 503이며 default policy, JWT permission 또는 role-only
fallback을 사용하지 않는다.

## Data and Migration

Operations policy version/head와 `operator_permission_grant`를 단독 migration한다. Plan 11이
`EXPIRED_BENEFIT_POLICY_READ`, `EXPIRED_BENEFIT_POLICY_WRITE`, `POINT_ACCOUNT_READ`,
`POINT_ADJUSTMENT` 네 값을 허용하는 closed permission vocabulary를 함께 만들며 Plan 14와
point adjustment plan은 이 constraint를 확장하거나 다시 만들지 않는다.

## API and Event Contracts

정책 GET은 `X-Access-Reason`, PATCH는 existing body의 non-blank `reason`만 적용한다. evidence는
operator grant lifecycle과 point adjustment에는 필수지만 expired-benefit policy PATCH body에는
추가하지 않는다. 정책 목록은
정확히 다섯 head를 반환하며 `PARTIAL_REFUND/COUPON`은 404다.

offline bootstrap은 read-only mounted token file의 OIDC workload identity를 required
issuer/audience/allowed-subject 설정으로 검증한다. 입력은 action, actorId, permission, reason,
evidence reference와 correlation ID이며 `APPLIED`만 exit 0이다. raw token·reason·evidence body를
stdout/stderr, log, DB 또는 Audit에 복제하지 않는다.

## Milestones

1. policy/grant schema와 five-head seed를 구현한다.
2. audited GET/PATCH와 bootstrap lifecycle을 구현한다.
3. CAS, revoke race, persistence-failure contract tests를 완료한다.

## Required Tests

- five-head cardinality, forbidden key 404, version snapshot/CAS replay
- role/grant/revoked combinations, GET reason/audit atomicity
- bootstrap absent/active/revoked/regrant, invalid issuer/audience/subject/expiry/token file과 rollback

## Validation Commands

```bash
./gradlew test --tests '*BenefitPolicy*' --tests '*OperatorPermission*'
./gradlew test --tests '*ModularityTests'
./gradlew clean build
bash scripts/verify-docs.sh
git diff --check
```

## Observability

policy/grant outcome metric은 permission/outcome closed tags만 사용한다.

## Documentation Updates

ADR-063/069/072, authorization matrix, OpenAPI와 Plan 12/14/30 successor evidence를 갱신한다.

## Progress

- [ ] policy/grant schema
- [ ] audited API and bootstrap
- [ ] concurrency/failure tests
- [ ] validation evidence

## Surprises & Discoveries

- 없음.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-08-01 | Accepted | policy와 generic grant를 Plan 11로 분리 | 환불과 support-read의 독립 vertical slice를 보장 | ADR-063, ADR-069 |
| 2026-08-01 | Accepted | 네 permission vocabulary와 OIDC workload-identity bootstrap을 Plan 11이 단독 소유 | grant migration 누락·중복과 static-secret/role fallback 방지 | ADR-069 |

## Outcomes & Retrospective

미구현 상태다. direct dependency인 Plan 00은 completed이고, permission vocabulary owner와 OIDC
workload-identity bootstrap 계약이 확정되어 implementation-ready다. ADR-072의 repository-wide
migration-writer lease를 얻기 전에는 schema 작업을 시작하지 않는다. 완료 전에는 Plan 12 policy
snapshot과 Plan 30 termination policy snapshot을 활성화하지 않는다.

## Revision Notes

- 2026-08-01: 기존 Plan 10의 Operations 범위를 분리했다.
- 2026-08-01: permission/trust-model 결정을 반영하고 implementation-ready로 승격했다.
