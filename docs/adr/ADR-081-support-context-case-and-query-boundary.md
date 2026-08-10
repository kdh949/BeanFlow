# ADR-081: Support Context, Case 중심 privileged action과 query boundary

- **Status:** Accepted
- **Date:** 2026-08-10

## Context

상담 Case와 교차 Context 조회·작업이 필요하지만 이를 Operations에 흡수하거나 Order 객체 그래프를 확장하면 수명·권한·소유권이 섞인다.

## Decision

독립 Support Context가 lightweight hybrid `SupportCase`, interaction/subject links, verification/action/approval UX와 resolution을 소유한다. Operations는 persistent grants, investigation, reconciliation과 LegalHold를 소유한다. Support는 owner Repository/table을 직접 접근하지 않고 공개 Application API와 ID만 사용한다. 검색·타임라인은 owner가 공개한 bounded DTO/query composition으로 구성한다. 저장·projection 방식과 endpoint별 filter/sort/cursor tuple은 owning Stage가 실제 owner model에 맞춰 제안하고 검증한다.

## Alternatives Considered

- Operations 하위 기능: 권한/복구와 상담 생명주기가 결합되어 기각.
- customer API impersonation: actor/audit 의미가 손상되어 기각.
- write Aggregate 연관관계/Elasticsearch 선도입: 경계·운영비가 증명되지 않아 기각.
- S00에서 공통 Support cursor tuple 고정: endpoint별 DTO와 모델이 없어 기각.

## Rationale

Case 감사 가능성과 owner 불변식을 보존하면서 modular monolith의 공개 API 경계를 따른다.

## Consequences

새 module/query surface가 필요하고 조합 조회 비용을 측정해야 한다. Support Console은 owner truth를 복제하지 않는다.

## Verification

Spring Modulith/ArchUnit dependency, Controller→Repository 금지, endpoint별 masked search/timeline contract와 dependency failure≠empty tests.

## Metrics

Case/search/timeline latency와 error, query count, projection freshness. 측정 전 개선을 주장하지 않는다.

## Revisit Conditions

독립 배포 요구, 구현된 query model의 측정된 한계 또는 fuzzy search 필요.

## Related Decisions

ADR-001, ADR-002, ADR-003, ADR-070.
