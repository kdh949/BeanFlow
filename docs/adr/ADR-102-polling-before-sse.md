# ADR-102: 주문보드 갱신을 조건부 Polling으로 시작한다

- **Status:** Accepted
- **Date:** 2026-08-11
- **Implementation owner:** [Store order board](../exec-plans/completed/productization-60-store-order-board.md)

## Context

점주 주문보드(`점주 1b`)와 고객 주문 추적(`고객 1d`)은 상태가 바뀌면 화면이 갱신돼야 한다.
점심 피크에는 수락 마감(2분 경고, 3분 timeout,
[ADR-015](ADR-015-store-acceptance-timeout-compensation.md))이 있어 신선도가 업무에 직접 영향을 준다.

선택지는 WebSocket, SSE, Polling이다. 현재 스택은 Spring MVC와 JPA이며 WebFlux를 쓰지 않는다.

## Decision

첫 버전은 **조건부 Polling**으로 시작한다.

### 계약

- 점주 주문보드는 초기값 **3초** 주기로 실행 주문을 조회한다. 주기는 클라이언트 상수로 고정하고,
  변경 시 측정 결과를 근거로 남긴다.
- 목록 API는 `ETag`를 반환하고 `If-None-Match`를 처리한다. 변경이 없으면 `304`를 반환하고 본문을
  전송하지 않는다.
- `ETag`는 정렬까지 완료한 canonical Projection의 SHA-256에서 파생한다. `serverTime`이나 매초 바뀌는
  countdown은 응답에 넣지 않고, `PAID`의 시간 상태는 `OPEN | WARNING | TIMEOUT_PENDING` phase로
  계산해 canonical 값에 포함한다. 그래서 DB row가 바뀌지 않아도 2분·3분 경계에서 ETag가 바뀐다.
- 첫 버전의 조건부 요청도 Projection 조회는 수행한다. ETag가 일치하면 serialization된 본문 전송만
  생략한다. `MAX(updated_at)+COUNT`만으로 304를 결정해 시간 경계나 같은 건수의 교체를 놓치지 않는다.
- 탭이 비활성이면 Polling을 중단한다. 복귀 시 즉시 1회 조회한다.
- 고객 주문 추적은 활성 주문이 있을 때만 Polling한다. 종료 상태에서는 중단한다.
- 상태 전이 명령의 응답은 갱신된 주문을 포함한다. 명령 직후 별도 조회를 하지 않는다.

### SSE 재검토 조건

다음 중 하나가 **실제 측정으로** 확인되면 SSE를 재검토한다.

- 보드 신선도가 업무 요구(수락 마감 대응)를 만족하지 못한다
- Polling으로 인한 DB CPU 또는 커넥션 사용이 다른 경로에 영향을 준다
- 매장 수 증가로 요청 수가 선형 이상으로 증가한다

### 금지 사항

- 주문보드 하나 때문에 WebFlux를 도입하지 않는다.
- WebSocket을 첫 버전에 도입하지 않는다.
- Polling 주기를 줄여 신선도 문제를 해결하려 하지 않는다. 먼저 측정한다.

## Alternatives Considered

### 1. WebSocket

- 장점: 지연이 가장 낮고 서버가 능동적으로 밀 수 있다.
- 단점: 연결 복구, 세션 관리, 메시지 순서, 인증 갱신, 프록시 설정이 모두 새 운영 대상이 된다.
  실패 모드가 HTTP보다 다양하고 관측이 어렵다. 현재 요구가 이 비용을 정당화하지 못한다.

### 2. SSE

- 장점: 서버 단방향 갱신에 적합하고 HTTP 위에서 동작한다.
- 단점: 연결 관리와 재연결 정책이 필요하다. 서버 인스턴스당 유지 연결 수가 늘고, 연결이 끊긴
  구간의 이벤트 보상이 필요하다. 어느 쪽이든 재연결 후 전체 조회를 하게 되는데, 그렇다면 처음부터
  조회가 정확한 상태를 만든다.

### 3. 조건부 Polling

- 장점: 실패 모델이 단순하다. 요청 하나가 실패해도 다음 주기에 복구된다. 인증·인가·관측이 기존
  HTTP 경로와 동일하다.
- 단점: 요청 수가 늘고 최대 지연이 주기만큼 존재한다.

## Rationale

Polling의 최대 갱신 지연은 주기와 같다. 3분 수락 정책을 근거로 3초를 **초기 가설**로 선택하지만,
문제가 없다고 미리 주장하지 않는다. 주문 생성부터 보드 반영까지의 시간과 timeout 대응 실패를
측정해 재검토한다. WebSocket·SSE가 추가하는 실패 모드는 그 측정 전에는 정당화되지 않는다.

`ETag` 조건부 요청은 변경이 없을 때 응답 본문 전송 비용을 줄인다. 첫 버전에는 Projection DB 비용이
그대로 남으므로 단점의 실제 크기를 작다고 단정하지 않고 SQL·serialization·network 비용을 따로
측정한다.

## Consequences

- 목록 API에 `ETag` 계산과 `If-None-Match` 처리가 추가된다.
- 정확한 ETag를 위해 304 응답에서도 Projection 조회 비용은 발생한다. 별도 변경 counter는 측정으로
  정당화되기 전에는 도입하지 않는다.
- 매장 수 × 주기만큼 요청이 발생한다. 이 수치를 지표로 관찰해야 한다.
- 클라이언트에 Polling 생명주기(탭 활성/비활성, 종료 상태) 코드가 추가된다.
- 화면 갱신이 최대 주기만큼 늦을 수 있다는 점을 UX 문구로 다루지 않는다. 3초는 사용자가 인지할
  수준이 아니기 때문이다.

## Verification

- 변경이 없을 때 Projection 조회 후 `304`가 반환되고 response body가 없는지 검증한다.
- 주문 상태가 바뀌면 다음 주기 안에 `200`과 새 `ETag`가 반환되는지 검증한다.
- DB 변경 없이 2분 warning과 3분 timeout 경계를 지날 때 새 `ETag`와 phase가 반환되는지 검증한다.
- Projection 조회·canonical hash 계산 실패가 full response fallback이 아니라 503인지 검증한다.
- 탭 비활성 시 요청이 중단되고 복귀 시 즉시 1회 조회하는지 프론트엔드 테스트로 검증한다.
- 종료 상태 주문에서 고객 추적 Polling이 중단되는지 검증한다.
- 동일 조건에서 매장 수와 주기를 바꿔가며 요청 수, DB CPU, p95를 측정한다.

## Metrics

- 주문보드 Polling RPS와 `304` 비율
- 보드 조회 p95
- 주문 상태 변경부터 보드 반영까지의 시간
- DB CPU와 커넥션 사용량(HikariCP active·pending)

## Implementation Results

2026-08-14 Plan 60에서 점주 보드에 3초 conditional polling을 구현했다. canonical
`StoreOrderBoard` JSON의 SHA-256 strong ETag를 반환하며, weak/comma-separated `If-None-Match`와 `*`도
처리한다. 동일 Projection은 304와 빈 body를 반환하고, hash 실패는 full response fallback 없이 503이다.
고정 Clock 통합 테스트에서 DB 변경 없이 warning·timeout 경계마다 phase와 ETag가 바뀌는 것을 확인했다.

브라우저 상태 테스트는 탭 hidden 동안 요청 0건, visible 복귀 즉시 현재 ETag를 포함한 1회 요청을
확인한다. 전이 성공은 command response item으로 열을 갱신해 추가 GET을 만들지 않고, 409만 현재 보드를
지운 뒤 unconditional 재조회한다. membership 403은 이전 보드와 선택을 지우고 ACTIVE 매장 목록을
다시 읽는다. SSE 재검토 조건을 충족하는 운영 부하 측정은 아직 없으므로 transport 결정은 유지한다.

## Revisit Conditions

- 위 "SSE 재검토 조건" 세 가지 중 하나가 실제 측정으로 확인될 때
- 고객 앱에 실시간 알림이 필요해 별도 채널이 이미 존재할 때
- 매장 수가 늘어 요청 수가 예산을 초과할 때

## Related Decisions

- [ADR-100](ADR-100-store-order-board-read-model.md)
- [ADR-015](ADR-015-store-acceptance-timeout-compensation.md)
- [ADR-019](ADR-019-notification-retry-and-manual-recovery.md)
