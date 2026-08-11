# Non-goals

초기 BeanFlow 범위에서 다음을 구현하거나 주장하지 않는다.

## Financial and payment scope

- 원본 카드번호, CVC 또는 민감 결제정보 저장
- 실제 고객 자금 보관
- 실제 가맹점 계좌 지급
- 전자금융 또는 카드 보안 규제 준수 완료 주장
- 실제 PG 운영 계약
- 복수 결제수단 provider routing
- Provider 지연·장애 시의 결제사 자동 전환([C-4](design-contract-conflicts.md))
- 저장 결제수단을 승인 원천으로 사용하는 원클릭 결제([ADR-101](../adr/ADR-101-payment-method-checkout-scope.md))
- 고객 환불 계좌 등록. 환불은 원 결제수단으로만 환급한다
- 실제 가맹점 입점 심사, 사업자등록 진위 확인, 계좌 실명 확인([ADR-105](../adr/ADR-105-sandbox-settlement-payout.md))
- 실제 이체 실행. 지급은 sandbox 지급 파일 생성까지만 다룬다

PG는 명시적인 mock/sandbox Adapter로 실패와 reconciliation을 검증한다.
scripted local adapter는 제품 provider나 운영 fallback이 아니다.

## Product scope

- 선불 지갑
- 실제 POS·프린터 장치
- 자체 라이더 모집·심사·배차 최적화와 라이더 앱
- 라이더 수입·정산과 장기 위치 궤적
- 전화·채팅 플랫폼, 실시간 상담원 자동 배분과 고급 SLA/Workforce Management
- 범용 고객센터 첨부·규칙 엔진과 측정 전 Elasticsearch 도입
- 원재료 BOM·발주·회계
- 광고 경매·개인화 플랫폼
- LLM의 자율 가격·정산·환불 변경
- 점주 AI 인사이트의 자동 실행(정원 변경·발주서 생성)([C-10](design-contract-conflicts.md))
- 휴대전화 OTP 기반 무비밀번호 로그인(P1, 실제 SMS Provider 계약 이후)
- 소셜 로그인과 비밀번호 재설정 발송
- 점주 임시 비밀번호의 사후 조회·복호화 저장. 놓친 값은 새 초기화로만 대체한다([BR-46](business-policy-decisions.md))

## Architecture scope

- 모든 Bounded Context의 독립 서비스화
- 필요성이 증명되지 않은 Kafka·Redis 도입
- Kubernetes 중심 운영
- 외부 의존성 실패 시 암묵적 로컬 fallback
- Session 저장소를 위한 Redis 즉시 도입([ADR-094](../adr/ADR-094-browser-session-security.md))
- 주문보드를 위한 WebFlux·WebSocket 도입([ADR-102](../adr/ADR-102-polling-before-sse.md))
- 매장·메뉴 검색을 위한 Elasticsearch 도입([ADR-103](../adr/ADR-103-store-search-strategy.md))
- 자체 Refresh Token 기반 JWT 발급 체계
- 서버 소유 장바구니(Cart) Aggregate. 첫 버전 장바구니는 클라이언트 상태다

외부 Delivery Provider를 사용하는 canonical `DeliveryFulfillment`, 최소 assignment snapshot,
Webhook Inbox, 상태 reconciliation과 Support incident는 위 비목표와 충돌하지 않는 포함 범위다.
범용 첨부 대신 암호화·접근통제·보존정책이 있는 제한형 `EvidenceReference`만 계획한다.

## Claims

- 실제 운영 사용자·트래픽이 없는 상태에서 프로덕션 운영을 주장하지 않는다.
- 합성 부하 테스트를 실제 사용자 트래픽으로 표현하지 않는다.
- 실행계획과 비교 측정 없이 성능 개선을 주장하지 않는다.
- 테스트 통과가 모든 운영 위험의 제거를 뜻한다고 표현하지 않는다.
