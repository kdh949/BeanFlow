# ADR-020: 가까운 매장 검색과 정밀 위치 최소 보존

- **Status:** Accepted
- **Date:** 2026-07-28
- **Implementation owner:** [Nearby Store Discovery](../exec-plans/active/nearby-store-discovery.md)

## Context

가까운 매장 검색에는 정밀 위·경도가 필요하지만 BR-28은 요청 처리 밖의 영구 저장과
로그·추적 노출을 금지한다.

## Decision

- latitude와 longitude는 nearby query의 요청 범위에서만 사용한다.
- 사용자 원본 좌표를 DB, cache, application log, trace attribute 또는 AuditRecord에
  저장하지 않는다.
- Discovery가 필요한 매장 좌표는 Merchant 소유 Store 위치를 검색용 Read Model로
  투영할 수 있다.
- **2026-08-01 Store profile ownership clarification:** 검색 가능한 매장 정보는
  `merchant_store` 쓰기 Entity에 검색 편의 필드를 추가하지 않고 Merchant가 별도 1:1
  `StoreDiscoveryProfile`로 소유한다. profile은 `store_id` PK/FK, 검증된 공개 매장명과
  `geography(Point,4326)` 위치를 가지며 GiST index를 둔다. Discovery는 영속 복제본이나
  동기화 event를 만들지 않고 Merchant public Query API가 반환하는 DTO projection만 소비한다.
- 기존 Store가 없으면 empty profile migration path를 허용한다. 기존 Store가 하나라도 있으면
  모든 Store에 검증 가능한 owner source의 profile이 있어야 endpoint를 활성화할 수 있다.
  unresolved row를 placeholder 이름, `(0,0)` 또는 임의 좌표로 보완하지 않고 migration/deployment와
  애플리케이션 시작을 실패시킨다.
- 검색 결과 분석은 선택된 storeId와 사전에 정의된 반경 구간처럼 비정밀 정보만 쓴다.
- PostgreSQL/PostGIS 장애를 빈 목록이나 local 거리 계산 fallback으로 바꾸지 않는다.

## Alternatives Considered

- 사용자 검색 좌표 장기 저장
- 좌표를 일반 request log에 포함
- 요청 전용 사용과 비정밀 분석
- `merchant_store`에 이름·geometry·검색 index 직접 추가
- Discovery-owned 영속 복제와 event/outbox 동기화

## Rationale

기능에 필요하지 않은 정밀 위치 보존과 노출 면적을 줄인다.

## Consequences

- 문제 조사 시 원본 사용자 좌표를 재생할 수 없다.
- test fixture는 합성 좌표만 사용하고 log redaction을 검증해야 한다.
- Merchant profile source가 없는 기존 Store는 Nearby 배포를 차단한다.
- Discovery 조회는 Merchant public Query API에 의존하지만 Merchant JPA Entity나 Repository를
  직접 사용하지 않는다.

## Verification

- DB schema, log와 trace에 원본 사용자 좌표 부재
- 잘못된 좌표·반경의 명시적 오류
- PostGIS 장애 시 503, 빈 성공 응답 없음
- empty/verified/unresolved Store profile inventory와 startup gate
- `merchant_store`에 검색용 이름·geometry가 추가되지 않고 별도 profile만 존재함

## Metrics

- **Not measured:** 검색 지연과 데이터 규모

## Revisit Conditions

명시적 동의를 받은 위치 개인화와 보존·삭제 정책이 도입될 때

## Related Decisions

- BR-28
- [ADR-002](ADR-002-bounded-context-boundaries.md)
- [ADR-009](ADR-009-explicit-failure-semantics.md)
