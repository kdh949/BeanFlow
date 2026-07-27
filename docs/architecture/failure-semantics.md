# Failure Semantics

## Principle

BeanFlow는 실패한 의존성이나 작업을 암묵적 fallback으로 숨기지 않는다. 모든 실패는 API 오류, 도메인 상태, health 상태, metric, retry 상태 또는 운영자 case 중 하나 이상으로 관측 가능해야 한다.

## Failure classes

### Required startup dependency

Examples:

- DB 설정 누락
- 유효하지 않은 signing key
- 필수 Provider credential 누락
- 운영 profile에서 fake PG 활성화

Behavior:

- 애플리케이션 시작 실패
- secret을 노출하지 않는 실행 가능한 오류 메시지
- 부분 구성 상태로 서비스 시작 금지

### Request-critical dependency

Examples:

- 주문 생성 중 DB 장애
- 재고 예약 저장 실패
- 결제 승인 요청 결과 불명

Behavior:

- business success를 반환하지 않음
- 필요한 경우 `UNKNOWN` 또는 `RECONCILING` 상태 보존
- 문서화된 API error 또는 pending/unknown 응답
- correlation ID 제공
- 외부 결과가 불명확하면 reconciliation 생성

### Asynchronous side effect

Examples:

- 준비 완료 알림 실패
- 이벤트 publication worker 장애
- Analytics projection 실패

Behavior:

- 이미 완료된 원본 거래를 부수효과 실패만으로 롤백하지 않음
- `RETRY_SCHEDULED`, `FAILED`, `MANUAL_REVIEW` 같은 영속 상태
- retry count와 last failure 노출
- 원본 거래 성공을 부수효과 성공으로 간주하지 않음

### Optional capability

fallback은 제품이 명시적으로 degraded mode를 지원할 때만 허용한다.

필수 조건:

- 이름 있는 fallback policy
- 명확한 활성화 조건
- 응답 또는 health에 degraded 상태 표시
- metric과 structured log
- 활성화·복구 자동 테스트
- Accepted ADR

## Forbidden patterns

- catch-all exception 후 성공 응답
- 실패를 `0`, 빈 목록, null 또는 stale cache로 정상처럼 반환
- DB 장애 시 in-memory repository 자동 전환
- Provider 설정 누락 시 fake/local Adapter 자동 전환
- 이벤트 발행 실패를 삼키고 완료 처리
- timeout을 확정 실패로 단정
- HTTP 200을 반환하고 로그만 남김
- 기본 credential 또는 secret 사용
- 알림 실패 때문에 주문 완료를 롤백
- 캐시 장애를 cache miss와 동일하게 취급하고 관측하지 않음

## BeanFlow examples

| Situation | Required behavior | Forbidden behavior |
|---|---|---|
| PG timeout | `Payment.UNKNOWN`, reconciliation | 실패 확정 또는 성공 반환 |
| PG success, DB write fail | 운영 복구 case와 Provider 조회 | 새 결제 자동 승인 |
| Notification provider fail | Order `READY`, Delivery retry state | Delivery 성공 처리 |
| DB unavailable | request/readiness failure | local DB 전환 |
| Redis unavailable | Accepted ADR에 정의된 동작 | local Map 전환 |
| Outbox worker down | Outbox pending과 alert | published로 간주 |
| Required env missing | startup failure | 임의 기본값 |
| Production profile with mock PG | startup failure | mock으로 계속 실행 |

## Test requirements

새 failure path는 다음을 검토한다.

- 실패가 사용자에게 어떤 상태·오류로 보이는가
- DB에 어떤 상태가 남는가
- 재시도가 같은 부작용을 만들지 않는가
- 운영자가 어떻게 발견하고 복구하는가
- metric, log와 correlation이 존재하는가
- fallback이 활성화되지 않았음을 어떻게 검증하는가
