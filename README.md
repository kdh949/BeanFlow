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

초기 도메인·아키텍처 기준을 정리하는 단계다. 아직 측정하지 않은 성능, 실제 운영 규모, 프로덕션 안정성을 주장하지 않는다.
