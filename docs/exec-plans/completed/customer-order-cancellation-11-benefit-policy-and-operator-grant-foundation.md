# 만료 혜택 정책과 operator grant foundation을 만든다

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/completed/customer-order-cancellation-00-contract-baseline.md`
> **Completed-At:** `2026-08-01`

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

2026-08-01 구현 시작 시 active Codex task와 Git worktree를 다시 감사했다. 같은 BeanFlow
저장소의 다른 active task는 `Writes-Migration=false`인 signed-cursor foundation뿐이고, 현재
worktree와 `main`/`origin/main`은 모두 `e9405a6`이며 마지막 migration은 `V12`다. 따라서 이 Goal이
repository-wide migration-writer lease의 유일 holder인 상태에서 다음 번호 `V13`을 사용한다.
번호 reservation, 기존 migration 재작성, checksum repair는 사용하지 않는다.

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

authorization/grant 보안 outcome metric은 permission/outcome closed tags만 사용한다. policy read는
outcome만, ADR-041의 policy change domain metric은 closed trigger/benefitType/mode/outcome만 사용하며
actor, reason, evidence, idempotency key와 policy version을 tag에 넣지 않는다.

## Documentation Updates

ADR-063/069/072, authorization matrix, OpenAPI와 Plan 12/14/30 successor evidence를 갱신한다.

## Progress

- [x] 2026-08-01 구현 전 계약·코드·lease 감사
  - 변경 목적: 다섯 policy head/version과 explicit operator grant/OIDC bootstrap을 Operations
    vertical slice 하나로 구현해 부분 환불·종료·support endpoint의 보안 foundation을 제공한다.
  - 도메인 불변식: 허용 policy key는 종료용 네 key와 `PARTIAL_REFUND×POINTS`뿐이고 version은
    append-only, head는 expected version CAS다. permission은 Accepted 네 값 외 값을 저장하지 않으며
    role/JWT claim/default seed/cache/direct SQL은 authorization source가 아니다.
  - 영향 모듈·파일: Operations API/internal policy·Audit와 새 permission/bootstrap service,
    shared Security의 coarse role boundary, `V13`, deployed OpenAPI, PostgreSQL/security tests와
    운영 runbook이다. Ordering의 두-policy Case/event 연결은 Plan 30 소유로 남긴다.
  - 트랜잭션·lock: policy HTTP는 active grant row를 먼저 잠그고 policy head/query와 Audit을 같은
    local transaction에서 flush한다. bootstrap identity 검증은 DB context/transaction 전에 끝내고,
    검증 뒤 actor+permission key와 grant row를 직렬화해 grant state/version과 Audit을 함께 commit한다.
  - 대안·선택 근거: JWT permission/default grant/static secret은 ADR-069를 위반해 제외한다. 새
    dependency 대신 기존 Spring Security/Nimbus와 read-only mounted token/JWKS file을 사용한다.
    absent grant와 cross-head idempotency 경쟁은 PostgreSQL transaction advisory key lock으로
    직렬화하고 실제 authorization은 grant row lock으로 선형화한다.
  - 실패 가능성: missing head/version, CAS loss, idempotency cross-key reuse, revoke race, Audit/flush
    failure, invalid issuer/audience/subject/exp/nbf와 unreadable/writable token/JWKS file은 각각
    404/409/403/503 또는 bootstrap non-zero로 끝나며 partial state와 fallback을 남기지 않는다.
  - 테스트 계획: empty database migration seed/constraint, immutable version, forbidden key,
    CAS/replay, role/grant/revoke/regrant, GET reason/Audit, persistence trigger failure, OIDC RSA fixture와
    bootstrap rollback을 PostgreSQL Testcontainers에서 검증한 뒤 지정 Gradle/docs/diff 명령을 실행한다.
  - 문서 계획: target 계약은 유지하고 실제 controller와 deployed OpenAPI/runbook을 맞춘다. 완료 시
    Plan 11을 completed로 옮기고 Plan 12/14/30과 point-adjustment direct dependency path/readiness를
    같은 atomic diff에서 갱신한다.
- [x] 2026-08-01 policy/grant schema
  - V13이 legacy singleton을 global-sequence immutable keyed version과 다섯 composite head로 이관하고,
    closed four-permission grant table을 default grant 없이 만든다. empty PostgreSQL migration에서 seed
    cardinality와 allowed-key constraint를 확인했다.
- [x] 2026-08-01 audited API and bootstrap
  - keyed GET/PATCH가 role gate 뒤 active grant row lock, policy operation, Audit flush를 한 transaction으로
    조정한다. offline command는 Spring/DB 시작 전에 read-only token/JWKS의 RS256 signature와 required
    claims를 검증하며 APPLIED만 0을 반환한다. 새 production dependency와 grant HTTP API는 없다.
- [x] 2026-08-01 concurrency/failure tests
  - 실제 PostgreSQL에서 immutable version, forbidden key, replay/stale CAS, role/claim/grant/revoke/regrant,
    grant row revoke 경쟁, GET/PATCH/grant Audit rollback, signature/issuer/audience/subject/exp/nbf/token file,
    verified bootstrap 성공·pre-transaction failure와 closed metric tag를 포함한 13개 targeted test가 통과했다.
- [x] 2026-08-01 validation evidence
  - `./gradlew test --tests '*BenefitPolicy*' --tests '*OperatorPermission*'`: PASS, 13 tests.
  - `./gradlew test --tests '*ModularityTests'`: PASS, 1 test.
  - `./gradlew clean build`: PASS, 전체 test와 Spotless 포함.
  - `bash scripts/verify-docs.sh`: PASS, target/deployed OpenAPI, 32 business policies, 72 ADRs,
    133 Markdown files와 23 ExecPlans.
  - `git diff --check`: PASS.

## Surprises & Discoveries

- current `V8`/JPA는 singleton boolean head, `head + 1` version ID와 role-only 단건 GET/PATCH를
  구현한다. target OpenAPI는 이미 five-head/keyed PATCH 계약이라 구현·deployed OpenAPI가 Accepted
  계약보다 뒤처진 drift이며 정책 재결정 사항은 아니다.
- `operations_audit_record.reason`은 160자지만 policy PATCH target 계약은 500자다. V13에서
  audit reason을 500자로 넓히지 않으면 유효한 요청이 Audit 저장 실패로 503이 되므로 같은 forward
  migration에서 비파괴 확장한다.
- bootstrap OIDC signature 검증은 이미 포함된 Spring Security/Nimbus로 구현할 수 있어 새 production
  dependency가 필요하지 않다.
- bootstrap 구성에 `@SpringBootConfiguration`을 사용하면 인접 Operations test의 application root로
  오탐되어 main context가 깨졌다. ordinary configuration과 전용 profile로 격리해 main component scan과
  offline 최소 context를 분리했다.
- ADR-022의 일반 manual reason 보존 규칙과 이 Goal의 bootstrap raw reason 복제 금지는 verified release
  principal action에 표준 `VERIFIED_RELEASE_OPERATOR_PERMISSION_CHANGE` reason을 쓰고 evidence reference만
  Audit에 보존하는 명시적 예외로 조정했다.
- 두 번째 `main` 함수가 생기자 Spring Boot `resolveMainClassName`이 main application과 offline CLI를
  구분하지 못했다. bootJar의 main을 `BeanflowApplicationKt`로 고정하고 CLI는 별도 JavaExec task로
  유지해 application artifact와 offline entrypoint를 명시적으로 분리했다.
- offline 최소 context에서도 runtime classpath의 Modulith actuator/event/observability auto-configuration이
  main `ApplicationModulesRuntime`을 요구하거나 불필요한 background infrastructure를 만들 수 있었다.
  bootstrap source는 Audit/grant/JPA만 import하고 관련 Modulith auto-configuration을 명시적으로 제외했다.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-08-01 | Accepted | policy와 generic grant를 Plan 11로 분리 | 환불과 support-read의 독립 vertical slice를 보장 | ADR-063, ADR-069 |
| 2026-08-01 | Accepted | 네 permission vocabulary와 OIDC workload-identity bootstrap을 Plan 11이 단독 소유 | grant migration 누락·중복과 static-secret/role fallback 방지 | ADR-069 |
| 2026-08-01 | Applied | 유일 migration-writer lease에서 main의 V12 다음 V13 사용 | ADR-072의 latest-main single-writer 규칙과 번호 비예약 원칙 준수 | ADR-072, repository/task inventory |
| 2026-08-01 | Applied | workload token과 JWKS를 read-only mounted file로 검증하고 signed deployment-run claim을 principal reference에 포함 | 네트워크·static secret fallback 없이 signature와 immutable run identity를 transaction 전에 검증 | ADR-069 |
| 2026-08-01 | Applied | bootstrap 자유 입력 reason은 검증 후 폐기하고 표준 lifecycle Audit reason과 evidence reference만 보존 | raw reason/evidence body의 DB·Audit 복제를 막으면서 grant 변경의 주체·source·승인 근거 연결 유지 | ADR-022, ADR-069 |
| 2026-08-01 | Applied | offline context는 Audit/grant JPA bean만 import하고 Modulith runtime/event/observability auto-configuration을 제외 | 운영 web/scheduler/event runtime을 시작하지 않는 좁은 fail-closed bootstrap 경계 유지 | ADR-069, bootstrap runbook |

## Outcomes & Retrospective

완료됐다. V13은 global sequence의 immutable policy version, trigger×benefit composite CAS head와
default grant가 없는 closed-vocabulary `OperatorPermissionGrant`를 만든다. legacy singleton은
`STORE_REJECTION/COUPON`으로 보존하고 종료용 나머지 세 head와 `PARTIAL_REFUND/POINTS`를 추가해 정확히
다섯 head를 seed한다.

deployed GET/PATCH는 target OpenAPI와 일치한다. coarse `PLATFORM_OPERATOR` role 뒤 active READ/WRITE
grant row lock, policy read/change와 Audit flush가 같은 transaction의 commit gate다. offline bootstrap은
read-only token/JWKS의 RS256 OIDC identity를 DB transaction 전에 검증하며 grant/revoke/regrant와 Audit를
원자적으로 저장한다. 새 grant HTTP API, production dependency, default/role/claim/cache/static-secret
fallback은 추가하지 않았다.

실제 PostgreSQL 테스트는 empty migration/seed, forbidden key, immutable version, CAS replay, role/grant/
revoke/regrant, revoke race, GET/PATCH/grant Audit rollback, invalid signature/issuer/audience/subject/exp/nbf/
token file와 bootstrap transaction-before rejection을 검증했다. Plan 12, Plan 14, Plan 30과 point-adjustment
plan의 Plan 11 dependency는 충족됐지만 각 plan의 나머지 direct dependency가 active이므로 모두
`Implementation-Ready=false`를 유지한다.

## Revision Notes

- 2026-08-01: 기존 Plan 10의 Operations 범위를 분리했다.
- 2026-08-01: permission/trust-model 결정을 반영하고 implementation-ready로 승격했다.
- 2026-08-01: V13, audited policy API, OIDC bootstrap과 PostgreSQL/graph validation을 완료하고 completed로 이동했다.
