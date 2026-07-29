# BeanFlow

BeanFlow는 다점포 카페의 선주문부터 결제, 픽업, 포인트, 정산, 환불 조정과 이의제기까지 이어지는 거래 생명주기를 다루는 플랫폼이다.

카페 선주문은 단순한 주문 CRUD가 아니다. 짧은 시간 안에 메뉴 가격, 픽업 수용량, 판매 재고, 쿠폰, 포인트, 외부 결제와 정산이 서로 다른 실패 모델 속에서도 일관성을 유지해야 한다.

BeanFlow는 다음 원칙을 중심으로 이 문제를 해결한다.

- 명시적인 Bounded Context와 Aggregate 경계
- 주문 당시 가격·정책 스냅샷
- 멱등 결제와 결과 불명 상태의 reconciliation
- 확정 정산을 덮어쓰지 않는 조정 원장
- 중복 이벤트와 재처리를 가정한 소비자
- 실패를 숨기지 않는 상태·오류·관측 체계
- 실제 PostgreSQL을 사용한 통합·동시성·실행계획 검증

## 문서

- [제품 개요](docs/product/product-overview.md)
- [비즈니스 정책](docs/product/business-policy-decisions.md)
- [End-to-End 흐름](docs/product/end-to-end-flow.md)
- [아키텍처 개요](docs/architecture/architecture-overview.md)
- [실패 의미론](docs/architecture/failure-semantics.md)
- [의사결정 기록 규칙](docs/decisions/README.md)
- [테스트 전략](docs/testing/test-strategy.md)

## 현재 상태

구현됨:

- 주문 시점 메뉴·옵션·가격 snapshot과 쿠폰 후 포인트 배분
- 픽업 슬롯·재고·쿠폰·포인트 원자 예약과 5분 lease 만료
- 주문 생성 멱등성, 감사 기록과 `BENEFIT_ONLY` 결제
- 외부 결제 승인 Tx1/Provider/Tx2 분리
- 명시 거절 취소·예약 해제, `UNKNOWN` 조회 reconciliation
- 늦은 승인 void/refund 복구와 5회 후 `MANUAL_REVIEW`

진행 중:

- 실제 PG adapter와 결제수단 등록·폐기 API
- 외부 결제 운영 부하·장애 주입 측정

예정:

- 매장 수락·거절과 timeout
- 알림 outbox, 주문 준비·완료와 포인트 적립
- 정산, 환불, 정산 조정과 이의제기

아직 측정하지 않은 실제 운영 규모나 프로덕션 안정성을 주장하지 않는다.

## 로컬 검증

Java 21과 실행 중인 Docker daemon이 필요하다. 애플리케이션 직접 실행에는 PostgreSQL과
JWT 공개키 endpoint를 명시해야 하며, 설정 누락 시 시작이 실패한다.

```bash
export BEANFLOW_DB_URL='jdbc:postgresql://localhost:5432/beanflow'
export BEANFLOW_DB_USERNAME='beanflow'
export BEANFLOW_DB_PASSWORD='replace-me'
export BEANFLOW_JWK_SET_URI='http://localhost:8081/.well-known/jwks.json'

./gradlew clean build
bash scripts/verify-docs.sh
git diff --check
```

scripted 결제 adapter는 명시적인 `local` profile 또는 테스트에서만 활성화된다.
운영 profile에는 실제 `PaymentGateway` 구성이 필요하며 fake/sandbox로 자동
대체되지 않는다. 결제수단 등록 API는 아직 없으므로 local 확인용 결제수단은
토큰 reference만 가진 개발 fixture로 준비해야 한다. PAN, CVC와 전체 유효기간을
저장하지 않는다.

현재 구현된 HTTP endpoint:

```text
POST /api/v1/orders
GET  /api/v1/orders/{orderId}
POST /api/v1/orders/{orderId}/payment-confirmations
```
