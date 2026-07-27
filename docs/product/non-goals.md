# Non-goals

초기 BeanFlow 범위에서 다음을 구현하거나 주장하지 않는다.

## Financial and payment scope

- 원본 카드번호, CVC 또는 민감 결제정보 저장
- 실제 고객 자금 보관
- 실제 가맹점 계좌 지급
- 전자금융 또는 카드 보안 규제 준수 완료 주장
- 실제 PG 운영 계약

PG는 명시적인 mock/sandbox Adapter로 실패와 reconciliation을 검증한다.

## Product scope

- 선불 지갑
- 실제 POS·프린터 장치
- 배달기사 배차와 라이더 앱
- 원재료 BOM·발주·회계
- 광고 경매·개인화 플랫폼
- LLM의 자율 가격·정산·환불 변경

## Architecture scope

- 모든 Bounded Context의 독립 서비스화
- 필요성이 증명되지 않은 Kafka·Redis 도입
- Kubernetes 중심 운영
- 외부 의존성 실패 시 암묵적 로컬 fallback

## Claims

- 실제 운영 사용자·트래픽이 없는 상태에서 프로덕션 운영을 주장하지 않는다.
- 합성 부하 테스트를 실제 사용자 트래픽으로 표현하지 않는다.
- 실행계획과 비교 측정 없이 성능 개선을 주장하지 않는다.
- 테스트 통과가 모든 운영 위험의 제거를 뜻한다고 표현하지 않는다.
