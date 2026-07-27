# ADR-020: 가까운 매장 검색과 정밀 위치 최소 보존

- **Status:** Accepted
- **Date:** 2026-07-28

## Context

가까운 매장 검색에는 정밀 위·경도가 필요하지만 BR-28은 요청 처리 밖의 영구 저장과
로그·추적 노출을 금지한다.

## Decision

- latitude와 longitude는 nearby query의 요청 범위에서만 사용한다.
- 사용자 원본 좌표를 DB, cache, application log, trace attribute 또는 AuditRecord에
  저장하지 않는다.
- Discovery가 필요한 매장 좌표는 Merchant 소유 Store 위치를 검색용 Read Model로
  투영할 수 있다.
- 검색 결과 분석은 선택된 storeId와 사전에 정의된 반경 구간처럼 비정밀 정보만 쓴다.
- PostgreSQL/PostGIS 장애를 빈 목록이나 local 거리 계산 fallback으로 바꾸지 않는다.

## Alternatives Considered

- 사용자 검색 좌표 장기 저장
- 좌표를 일반 request log에 포함
- 요청 전용 사용과 비정밀 분석

## Rationale

기능에 필요하지 않은 정밀 위치 보존과 노출 면적을 줄인다.

## Consequences

- 문제 조사 시 원본 사용자 좌표를 재생할 수 없다.
- test fixture는 합성 좌표만 사용하고 log redaction을 검증해야 한다.

## Verification

- DB schema, log와 trace에 원본 사용자 좌표 부재
- 잘못된 좌표·반경의 명시적 오류
- PostGIS 장애 시 503, 빈 성공 응답 없음

## Metrics

- **Not measured:** 검색 지연과 데이터 규모

## Revisit Conditions

명시적 동의를 받은 위치 개인화와 보존·삭제 정책이 도입될 때

## Related Decisions

- BR-28
- [ADR-002](ADR-002-bounded-context-boundaries.md)
- [ADR-009](ADR-009-explicit-failure-semantics.md)
