# 결제수단 lifecycle 리뷰 결함을 보안·복구·동시성 경계에서 수정한다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** `docs/exec-plans/completed/payment-method-token-management.md`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

결제수단 lifecycle 구현의 리뷰에서 확인된 다섯 결함을 수정한다. 검증 실패 응답은 raw
`authKey`를 반사하지 않아야 하고, lookup 없는 등록 결과 불명은 수동 검토로 종결돼야 한다.
롤링 배포의 새 인스턴스는 다른 인스턴스가 처리 중인 fresh claim을 회수하지 않아야 하며,
Provider DELETE와 검증된 `BILLING_DELETED` 알림은 어떤 순서로 끝나도 고객 DELETE와 원장이
stored `204` 한 결과로 수렴해야 한다.

## Current State

- `ApiExceptionHandler.invalidRequest()`가 framework exception message를 `details.reason`으로
  직렬화해 rejected value를 반사한다.
- registration `Unknown`, result 저장 실패와 startup recovery가 `REGISTRATION_UNKNOWN`만 저장한다.
  registration에는 deadline worker가 없어 같은 key가 영구 202에 머문다.
- startup recovery query가 `claim_started_at` cutoff 없이 모든 `PROCESSING` row를 반환한다.
- deactivation claim/result/recovery/deadline은 work를 먼저 잠근 뒤 PaymentMethod를 잠근다.
  W2는 active work를 먼저 조회한 뒤 PaymentMethod를 잠가 D1의 미커밋 work를 놓칠 수 있다.
- W2가 work를 `COMPLETED`로 만든 뒤 Provider success가 돌아오면 D2의 claim 검증이 503을 만든다.

## Definitions

- **fresh claim:** `claim_started_at`이 recovery cutoff보다 뒤인 `PROCESSING` claim이다.
- **stale claim:** `claim_started_at <= now - claimStaleAfter`인 `PROCESSING` claim이다.
- **linearization point:** PaymentMethod row의 pessimistic write lock을 획득한 시점이다.
- **stored 204:** deactivation work가 `COMPLETED`, first response가 정확히 `204`와 빈 body이고
  PaymentMethod가 `DEACTIVATED`인 terminal 결과다.

## Scope

### In Scope

- 공통 validation/binding 오류의 안전한 closed detail 구성
- registration direct Unknown, result 저장 실패와 stale startup recovery의 즉시 `MANUAL_REVIEW`
- `claim_started_at` 기반 configurable stale cutoff와 fresh claim 보존
- 모든 deactivation mutation의 `PaymentMethod → work` lock order
- W2의 method lock 이후 active work 재조회와 D2의 stored-204 멱등 수렴
- 실제 PostgreSQL을 사용하는 API·상태·retention·동시성 회귀 테스트
- 관련 transaction, failure, data-handling 문서와 implementation evidence 정정

### Non-goals

- 새 Provider lookup, Provider 재호출, live Toss adapter와 webhook transport
- claim owner/heartbeat/lease 컬럼과 Flyway migration
- PaymentMethod 운영자 manual-review 해소 API
- PointAccount PR 리뷰 항목 수정

## Business Rules and Invariants

- raw/hash `authKey`, rejected value와 Provider 내부 값은 response/log/trace/metric/Audit에 없다.
- lookup 없는 registration Unknown은 추가 Provider 호출 없이 `MANUAL_REVIEW`와
  `REGISTRATION_DELAYED` 고객 표현으로 종결한다.
- `MANUAL_REVIEW` registration은 retention cutoff가 없고 운영 해소 전에 정리되지 않는다.
- fresh PROCESSING claim은 startup recovery가 바꾸지 않는다. stale claim만 외부 호출 없이 복구한다.
- D1, DC, D2, persistence-failure recovery, deadline worker와 W2는 PaymentMethod를 먼저 잠근다.
- verified notification이 먼저 stored 204를 commit했다면 뒤 Provider 결과는 새 Audit·metric·상태
  전이 없이 같은 204를 반환한다.

## Architecture and Transaction Boundaries

Provider 호출은 계속 claim transaction과 result transaction 사이, DB transaction 밖에서 실행한다.
startup recovery는 `claim_started_at <= now - claimStaleAfter`인 ID만 조회한 뒤 각각 별도 local
transaction에서 다시 상태와 cutoff를 검증한다. 기본 `claimStaleAfter`는 5분이며 실제 Provider
adapter의 전체 connect/read timeout과 grace 합보다 길어야 한다.

deactivation ID만 가진 경로는 먼저 non-locking projection으로 `paymentMethodId`를 읽고,
PaymentMethod row를 잠근 뒤 work row를 잠근다. W2는 token advisory lock과 binding 조회로 target ID를
구한 뒤 PaymentMethod를 잠그고 active work를 조회한다. D1이 먼저면 W2가 commit된 work를 함께
완료하고, W2가 먼저면 D1이 inactive 상태를 보고 work를 만들지 않는다.

## Alternatives Considered

- claim owner/lease 컬럼: stronger ownership을 주지만 새 migration과 heartbeat/instance identity가
  필요해 현재 provider-neutral scripted 구현의 리뷰 수정 범위를 넘는다.
- cutoff 없는 startup recovery: 빠르지만 롤링 배포의 live claim을 훼손해 거절한다.
- registration deadline worker: lookup도 자동 복구도 없으므로 지연시킬 제품 가치 없이 202 정체만
  늘려 즉시 manual review를 택한다.
- W2와 D1에 별도 advisory lock 추가: 기존 row lock을 공통 linearization point로 통일하는 것보다
  잠금 체계가 늘어나 거절한다.

## Failure Semantics

- framework validation 예외는 `INVALID_REQUEST`, 안전한 고정 message와 field/closed reason만 반환한다.
- registration Unknown의 내부 reason은 direct result, persistence failure, interrupted stale claim을
  닫힌 값으로 구분하되 고객에게는 모두 `PROCESSING + REGISTRATION_DELAYED`만 보인다.
- fresh claim은 recovery success로 기록하지 않는다. DB lock/query 실패는 503/worker failure로 남긴다.
- completed work가 exact stored 204가 아니거나 method terminal 상태와 모순이면 성공으로 추정하지
  않고 `DEPENDENCY_UNAVAILABLE`로 실패한다.

## Data and Migration

Migration을 쓰지 않는다. 기존 `claim_started_at`, `manual_review_reason`, first response와 retention
제약을 사용한다. registration `MANUAL_REVIEW`와 deactivation non-terminal work의
`retention_expires_at`은 계속 null이다.

## API and Event Contracts

OpenAPI schema/status는 바뀌지 않는다. validation `details`는 raw exception message 대신 선택적 field와
`INVALID_VALUE|MISSING_VALUE|INVALID_FORMAT|MALFORMED_REQUEST` 중 하나의 reason만 사용한다.
registration 202는 `noticeCode=REGISTRATION_DELAYED`, deactivation terminal은 body 없는 204다.

## Milestones

1. oversized authKey marker를 반사하는 API 테스트를 RED로 만들고 common handler를 안전하게 바꾼다.
2. registration direct Unknown/replay/retention과 fresh/stale startup recovery를 RED로 만든 뒤 종결과
   cutoff를 구현한다.
3. notification-before-provider-result와 D1/W2 interleaving을 RED로 만든 뒤 lock order와 204 convergence를
   구현한다.
4. 집중·구조·전체 build와 문서 검증을 실행하고 plan을 completed로 이동한다.

## Required Tests

- oversized authKey의 marker가 400 body에 없고 detail이 `authKey/INVALID_VALUE`만 포함
- direct Unknown과 persistence-failure registration이 `MANUAL_REVIEW`, delayed 202, same-key 무호출 replay
- registration manual row의 retention null과 cleanup 비삭제
- fresh registration/deactivation PROCESSING 보존, stale 두 claim만 recovery
- 다른 인스턴스 역할의 fresh provider call과 startup recovery interleaving에서 claim/result 보존
- W2가 Provider result보다 먼저 commit해도 최초 DELETE가 204로 종료
- D1 work insert가 미커밋인 동안 W2가 경쟁해도 work가 함께 COMPLETED되고 후속 Provider 호출 없음
- PaymentMethod 집중 테스트, Modulith/architecture, clean build, docs/OpenAPI parity

## Validation Commands

- `./gradlew test --tests '*PaymentMethodControllerIntegrationTest'`
- `./gradlew test --tests '*PaymentMethodApplicationServiceIntegrationTest' --tests '*PaymentMethodProviderNotificationIntegrationTest'`
- `./gradlew test --tests '*ModularityTests' --tests '*Architecture*'`
- `./gradlew clean build`
- `bash scripts/verify-docs.sh`
- `git diff --check`

## Observability

기존 closed-tag metric만 사용한다. 민감 marker, raw exception message, claim token과 Provider reference를
새 log/metric/Audit에 추가하지 않는다. registration manual-review metric은 reason이 아닌 closed state만
tag로 기록한다.

## Documentation Updates

- `docs/architecture/transaction-boundaries.md`: stale cutoff와 method-first lock order
- `docs/architecture/failure-semantics.md`: lookup 없는 registration immediate manual review
- `docs/security/payment-method-data-handling.md`: validation response와 corrected recovery evidence
- `docs/decisions/minor-decisions.md`: common handler message echo가 제거된 뒤 nearby raw-string 결정의
  남은 canonicalization 근거
- 완료된 원 ExecPlan은 historical plan으로 유지하되 Outcomes의 구현 결함을 조용히 고치지 않고 이
  remediation plan과 실제 결과를 새 기록으로 남긴다.

## Progress

- [x] 리뷰 스레드와 Accepted source 대조
- [x] validation credential reflection 회귀 테스트와 수정
- [x] registration manual-review와 stale cutoff 회귀 테스트와 수정
- [ ] deactivation lock/convergence 회귀 테스트와 수정
- [ ] 전체 검증·push·review thread resolution

## Surprises & Discoveries

- 2026-08-10: nearby query는 같은 common handler의 raw message echo를 피하려고 raw String binding을
  선택한 기록이 있다. handler 수정은 결제수단뿐 아니라 공통 입력의 rejected value 반사를 닫는다.
- 2026-08-10: V37 제약은 registration `MANUAL_REVIEW`의 null retention과 closed reason을 이미 허용해
  즉시 종결에 migration이 필요하지 않다.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-08-10 | Accepted existing | lookup 없는 registration Unknown은 즉시 manual review | BR-29/ADR-079의 이미 닫힌 실패 의미 적용 | BR-29, ADR-079 |
| 2026-08-10 | Minor | startup recovery는 5분 stale cutoff를 사용 | live claim 보호, schema-free bounded recovery | 이 ExecPlan |
| 2026-08-10 | Accepted existing | deactivation mutation lock은 PaymentMethod→work | D1/W2 linearization과 deadlock 방지 | ADR-079 |

## Outcomes & Retrospective

진행 중이다.

## Revision Notes

- 2026-08-10: PR #48 리뷰 5건의 재현 근거와 code-only remediation 계획 작성.
