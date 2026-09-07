# 감사 기록 append 전용 persist 경로 PR 분리

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** —
> **Completed-At:** `2026-09-08`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

새 UUID 감사 기록에 merge가 수행하던 불필요한 존재 확인 SELECT를 제거한다.

## Current State

통합 구현과 배포에서 검증한 이 변경을 별도 PR로 추출한다. 다른 성능 수정의 코드를 포함하지 않는다.

## Definitions

append는 새 기록 추가, threshold는 시작한 요청의 성공뿐 아니라 목표 부하 미시작과 지연을 포함한 수용 조건이다.

## Scope

### In Scope

AuditRecordService가 생성한 새 entity만 `AuditRecordAppendRepository`의 persist/flush로 저장한다.
새 repository는 `@Repository`, `@Transactional(MANDATORY)`로 호출자의 transaction과 예외 변환을
유지한다. 조회 repository는 유지한다. 독립 operator/ordinary-policy bootstrap의 좁은 Spring
configuration에도 writer를 명시적으로 import한다. import 누락 회귀를 같은 PR에서 원자적으로 해결한다.

### Non-goals

타 PR의 코드, 업무 정책 숫자 변경, schema migration, 전체 용량 보장.

## Business Rules and Invariants

기존 거래 원자성, 중복 보호, 실패 노출 및 ADR의 불변식을 유지한다.

## Architecture and Transaction Boundaries

AuditRecordService가 생성한 새 entity만 `AuditRecordAppendRepository`의 persist/flush로 저장한다.
새 repository는 `@Repository`, `@Transactional(MANDATORY)`로 호출자의 transaction과 예외 변환을
유지한다. 조회 repository는 유지한다. 독립 operator/ordinary-policy bootstrap의 좁은 Spring
configuration에도 writer를 명시적으로 import한다. import 누락 회귀를 같은 PR에서 원자적으로 해결한다.


## Alternatives Considered

기존 동작 유지와 공통 기반 전체 병합을 검토했다. 측정된 비용/계약 문제만 독립 변경해 검토와 rollback 범위를 줄인다.

## Failure Semantics

append repository에 이미 존재하는 entity를 넘기면 실패해야 한다. 실패를 무시하거나 감사 저장을 비동기 성공으로 처리하지 않는다.

## Data and Migration

Flyway 변경 없음. 측정 DB를 삭제하거나 실패 상태를 성공으로 보정하지 않는다.

## API and Event Contracts

공개 API/event shape 변경 없음. append repository에 이미 존재하는 entity를 넘기면 실패해야 한다. 실패를 무시하거나 감사 저장을 비동기 성공으로 처리하지 않는다.

## Milestones

개별 회귀 확인 → 관련 ADR 및 구현 → 독립 브랜치 검증 → 실제 통합 부하와 캡처 → PR 증거 기록.

## Required Tests

추가 audit 3건의 statement 증가량은 기존 **6개 → 3개**다. 단순 INSERT 추가뿐 아니라 UUID existence
SELECT 제거를 Hibernate statistics로 검증했다. 기존 구현에서 이 회귀 assertion이 실패한 것을 확인했다.
새 감사 저장 경로, 중복 batch 전체 rollback, 원문 PII 거절, retention, 두 독립 bootstrap의 실제 append를 검증했다.
main 기반 분리 worktree에서 **30개 테스트 및 bootJar 통과**, failure/error/skipped 0.
통합 regression 110개와 추가 bootstrap/audit 23개에는 겹치는 테스트가 있으므로 합산하지 않는다.

[Spring Data JPA의 entity 상태 판별 계약](https://docs.spring.io/spring-data/jpa/reference/jpa/entity-persistence.html)에
따라 수동 UUID가 있는 새 append를 일반 save/merge 경로로 보내지 않도록 했다.


## Validation Commands

```bash
./gradlew --no-daemon -Pkotlin.incremental=false test --tests '*AuditRecordTest' --tests '*AuditRetentionPolicyIntegrationTest' --tests '*OperatorPermissionBootstrapApplicationTest' --tests '*OrdinaryPointAccrualPolicyBootstrapApplicationTest' --tests '*OperatorPermissionIntegrationTest' --tests '*OrdinaryPointAccrualPolicyBootstrapTest' bootJar
bash scripts/verify-docs.sh
```

## Observability

[측정 보고서](../../quality/performance-audit-append-2026-09-08.md)에 native 결과와 Grafana 캡처 및 비교 한계를 기록한다.

## Documentation Updates

[관련 ADR](../../adr/ADR-022-audit-record.md)과 측정 보고서를 포함한다.

## Progress

- [x] 코드/테스트/ADR을 변경별로 추출
- [x] 해당 경로 자동 검증 및 통합 배포/부하 확인
- [x] 측정 제한과 후속 문제 기록

## Surprises & Discoveries

SQL 또는 환불 계약 개선과 HTTP 처리량 개선은 다른 주장이다. 20/s 측정에서 전체 처리량 개선이 입증되지 않았다.

## Decision Log

2026-09-08: 개별 변경은 독립 PR로 만들고 integrated load를 단일 변경의 인과 효과로 사용하지 않는다.

## Outcomes & Retrospective

로컬 회귀와 배포 evidence를 기록했다. COMPLETED는 추출/검증 문서 완료이며 원격 PR 병합이나 전체 용량 검증 완료를 뜻하지 않는다.

## Revision Notes

2026-09-08: 수정별 PR 제출용으로 통합 구현의 범위·검증·미해결 사항을 정리했다.
