# Delivery Support Policy

> **Status:** Canonical Delivery/Provider boundary, reconciliation and no silent failover are Accepted in ADR-088;
> provider, authentication, state vocabulary and retention implementation remain DRAFT/blocked.

BeanFlow는 Provider 독립 `DeliveryFulfillment`, 최소 assignment snapshot, provider reference, incident와 reconciliation을 소유한다. 외부 Provider는 실제 배차·라이더 운영·위치 수집·운송을 소유한다.

물리 상태와 `IN_SYNC | STALE | UNKNOWN | RECONCILING | OUT_OF_SYNC | MANUAL_REVIEW` sync 상태를 분리한다. Provider create/cancel timeout은 확정 실패가 아니며 lookup/reconciliation으로 수렴한다. 이전 Provider 결과/취소가 확정되기 전 다른 Provider로 자동 전환하지 않는다.

Support는 masked rider name, relay contact, vehicle summary, current assignment와 ETA/status만 기본 조회한다. 실제 전화, 다른 delivery, 전체 profile, rider settlement, 장기 위치 궤적은 제외한다. 현재 좌표는 safety/misdelivery/unreachable/emergency 목적에만 grant하고 terminal+24시간 뒤 파기한다. 정확 주소/연락처 활성 사본은 terminal+90일 초기 정책이다.

자체 rider 모집·배차 알고리즘·rider app·수입/정산은 비목표다.
