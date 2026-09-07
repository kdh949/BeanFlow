# 픽업번호 카운터 정상 발급 경로 분리 PR 분리

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** `—`
> **Completed-At:** `2026-09-08`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

주문 생성이 stock/slot 잠금을 보유한 동안 매번 과거 주문 count/max를 집계하던 SQL 비용을 줄인다.

## Current State

통합 구현과 배포에서 검증한 이 변경을 별도 PR로 추출한다. 다른 성능 수정의 코드를 포함하지 않는다.

## Definitions

append는 새 기록 추가, threshold는 시작한 요청의 성공뿐 아니라 목표 부하 미시작과 지연을 포함한 수용 조건이다.

## Scope

### In Scope

기존 counter가 있으면 `UPDATE ... RETURNING`으로 원자 증가한다. counter가 없을 때만
기존 주문 count/max를 이용한 UPSERT를 실행한다. 동시 초기화에서는 기존 counter+1과 baseline 중
큰 값을 선택한다. owner transaction, rollback, 매장·영업일 유일성, legacy 최대값 보호를 유지한다.
존재하지만 외부 조작으로 뒤처진 counter를 매 주문마다 자동 복구하지 않는 경계는 ADR-097에 기록했다.

### Non-goals

타 PR의 코드, 업무 정책 숫자 변경, schema migration, 전체 용량 보장.

## Business Rules and Invariants

기존 거래 원자성, 중복 보호, 실패 노출 및 ADR의 불변식을 유지한다.

## Architecture and Transaction Boundaries

기존 counter가 있으면 `UPDATE ... RETURNING`으로 원자 증가한다. counter가 없을 때만
기존 주문 count/max를 이용한 UPSERT를 실행한다. 동시 초기화에서는 기존 counter+1과 baseline 중
큰 값을 선택한다. owner transaction, rollback, 매장·영업일 유일성, legacy 최대값 보호를 유지한다.
존재하지만 외부 조작으로 뒤처진 counter를 매 주문마다 자동 복구하지 않는 경계는 ADR-097에 기록했다.


## Alternatives Considered

기존 동작 유지와 공통 기반 전체 병합을 검토했다. 측정된 비용/계약 문제만 독립 변경해 검토와 rollback 범위를 줄인다.

## Failure Semantics

정상 counter의 정확성은 동일 transaction과 DB 제약으로 보호한다. 도입 이후 비정상 SQL 조작으로 counter를 뒤로 돌리는 경우는 운영 복구 대상이다.

## Data and Migration

Flyway 변경 없음. 측정 DB를 삭제하거나 실패 상태를 성공으로 보정하지 않는다.

## API and Event Contracts

공개 API/event shape 변경 없음. 정상 counter의 정확성은 동일 transaction과 DB 제약으로 보호한다. 도입 이후 비정상 SQL 조작으로 counter를 뒤로 돌리는 경우는 운영 복구 대상이다.

## Milestones

개별 회귀 확인 → 관련 ADR 및 구현 → 독립 브랜치 검증 → 실제 통합 부하와 캡처 → PR 증거 기록.

## Required Tests

같은 실제 DB/매장/영업일에서 old/new SQL을 번갈아 5회씩 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`
실행하고 각각 ROLLBACK했다. 첫 실행은 old 57.161ms/new 1.266ms로 별도 보존했다.
나머지 4회 중앙값은 **4.834ms → 0.036ms**, root plan shared hit blocks는 **345 → 3**이다.
이는 SQL 한 문장의 비교이며 HTTP 개선율이 아니다.

기존 구현에서 `ordering_order` 테이블을 잠근 회귀 테스트가 실패했고 새 정상 발급은 history를
읽지 않아 통과했다. main 기반 분리 worktree에서 allocator 9 + migration 9 + exhaustion 1 =
**19개 테스트 및 bootJar 통과**, failure/error/skipped 0. 최초 동시 20건·legacy 복원·transaction
rollback과 번호 고갈을 검증했다.


## Validation Commands

```bash
./gradlew --no-daemon -Pkotlin.incremental=false test --tests '*OrderDisplayIdentityAllocatorIntegrationTest' --tests '*OrderReferenceMigrationTest' --tests '*OrderReferenceExhaustionIntegrationTest' bootJar
bash scripts/verify-docs.sh
```

## Observability

[측정 보고서](../../quality/performance-pickup-counter-2026-09-08.md)에 native 결과와 Grafana 캡처 및 비교 한계를 기록한다.

## Documentation Updates

[관련 ADR](../../adr/ADR-097-store-pickup-number.md)과 측정 보고서를 포함한다.

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
