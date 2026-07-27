# Test Strategy

| Layer | Purpose | Primary risks |
|---|---|---|
| Domain Unit | 상태 전이, 금액·정책 | 잘못된 상태, 금액 불일치 |
| Application | 유스케이스·Port 순서 | 트랜잭션 경계, 보상 누락 |
| Repository | JPA, SQL, constraint, lock | H2 차이, Lost Update, mapping |
| API Contract | HTTP·오류·인가 | 계약 drift, 잘못된 status |
| Module / Architecture | 경계와 순환 | 내부 패키지 침범 |
| Concurrency | 경합·중복 | oversell, 초과 예약, 이중 사용 |
| Idempotency | 같은 명령·이벤트 반복 | 중복 결제·적립·정산 |
| Resilience | timeout·재시작·ACK 유실 | terminal state 누락, 숨은 실패 |
| Load | 지연·처리량·resource | pool 고갈, lock wait, GC |
| Time | 만료·영업시간·batch | 경계 시각 오류 |

## Environment

- Repository와 통합 테스트는 PostgreSQL Testcontainers를 기본으로 한다.
- 위치 기능은 PostGIS 이미지 또는 확장을 실제로 사용한다.
- 외부 PG·알림은 성공, 명시 거절, timeout, malformed response, ACK 유실을 재현 가능한 Adapter로 테스트한다.
- 운영 profile에서 fake Adapter가 활성화되지 않는 startup test를 둔다.

## Risk-first examples

- 같은 Idempotency-Key 100개 동시 요청 → 승인 부작용 한 번
- 남은 재고 1개에 동시 예약 → 성공 1개, oversell 0
- PG 성공 후 DB write 실패 → UNKNOWN/복구 case, 재승인 없음
- OrderCompleted 중복 전달 → 포인트·SettlementItem 한 번
- 알림 timeout 후 Provider는 성공 → ACK 유실 중복 발송 제어
- 정산 batch 중단 후 재실행 → 중복 Item 0
- DB 장애 → 빈 목록 또는 local repository fallback 없음

## Query tests

N+1은 FetchType 이름만으로 판단하지 않는다.

1. 필요한 API 필드 정의
2. 발생 SQL과 쿼리 수 관찰
3. Projection, Fetch Join, EntityGraph, Batch Fetch 비교
4. pagination, row duplication, memory 영향 확인
5. 회귀 테스트와 실행계획 저장
