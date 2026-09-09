# 성능 드라이버 결제 이력 보존 PR 분리

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** `docs/exec-plans/completed/perf-refund-contract-pr-extraction.md`
> **Completed-At:** `2026-09-08`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

10,000건 초과 시 과거 결제를 지우던 perf 드라이버가 기존 사실을 보존하도록 한다.

## Current State

환불 계약 수정은 선행 PR이다. 이 PR은 50,000건 보관 상한과 신규 요청의 명시적 포화만 추가한다.

## Definitions

retention은 같은 프로세스에서 lookup/replay/refund에 필요한 합성 결제 사실의 보존이다. 영속성은 아니다.

## Scope

### In Scope

자동 삭제 제거, 신규 승인 503, 테스트용 제한 용량과 경계 검증.

### Non-goals

업무 DB, 실제 Toss, 영속 저장소, 50,000건 실제 부하 용량 보장.

## Business Rules and Invariants

기존 결제 사실과 멱등 응답을 보존한다. 실패를 fake 성공으로 처리하지 않는다.

## Architecture and Transaction Boundaries

Node 단일 프로세스 perf driver만 변경한다. 애플리케이션 owner transaction은 변경하지 않는다.

## Alternatives Considered

eviction 유지와 영속 DB 추가 대신 신규 요청을 거절하는 명시적 bounded policy를 선택했다.

## Failure Semantics

신규 결제는 용량 초과 시 503 DRIVER_CAPACITY_EXCEEDED. 기존 조회·환불·재생은 계속 가능하다.

## Data and Migration

Flyway 없음. 재시작은 명시적 snapshot/restore 검증이 필요하다.

## API and Event Contracts

perf driver의 포화 오류만 명시한다. 공개 BeanFlow API/event 변경 없음.

## Milestones

기존 한도 확인 → no-eviction 구현 → 경계 회귀 → 배포 및 12,058건 조회 검증.

## Required Tests

경계 포함 10개 Node 테스트. 실제 이전8,270+신규3,788건 모두 GET 성공 및 기존 이력 보존.

## Validation Commands

```bash
node --test infra/perf/toss-driver.test.mjs
bash scripts/verify-docs.sh
```

## Observability

[보관 한도 보고서](../../quality/performance-driver-retention-2026-09-08.md)의 read-only 집계와
Grafana 환불 문맥을 구분한다. retained count 전용 panel은 현재 없다.

## Documentation Updates

ADR-121의 보관 경계와 관련 검증 보고서.

## Progress

- [x] 용량 정책 및 경계 회귀
- [x] 통합 배포와 10,000건 초과 조회 검증
- [x] 개별 PR 범위와 측정 제한 기록

## Surprises & Discoveries

포화 시 과거 사실 삭제는 오류 재현을 왜곡한다. HTTP 지연 개선과 이력 보존은 별개다.

## Decision Log

2026-09-08: 기존 사실을 지우는 대신 새로운 사실 생성을 명시적으로 거절한다.

## Outcomes & Retrospective

10개 테스트 및 12,058건 조회 확인. 50,000건 실제 부하/장기 메모리 안정성은 Not run이다.
COMPLETED는 코드 추출과 검증 기록 완료이며 원격 PR 병합 완료를 의미하지 않는다.

## Revision Notes

2026-09-08: 드라이버 환불 계약 변경과 보관 한도 변경을 별도 PR로 분리했다.
