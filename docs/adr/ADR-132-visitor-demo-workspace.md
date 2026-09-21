# ADR-132 방문자 체험 공간의 발급과 접근 기한

- Status: Accepted
- Date: 2026-09-15

## Context
기존 로그인과 주문 상태 전이를 유지하면서 회원가입 없는 공개 체험이 필요하다. 고정 공유 계정은 다른 방문자의 주문과 처리 상태를 노출한다.

## Decision
명시적으로 활성화한 테스트 환경에서만 Demo 모듈을 제공한다. 방문자별 신규 고객·점주·매장을 한 transaction에서 생성하고 기존 HttpOnly Session을 발급한다. 일반 Session이 있으면 발급을 거부하여 계정을 교체하지 않는다. Identity owner가 만료 시각과 허용 매장을 관리하고 모든 인증 및 주문 생성 시 검사한다.

기능 플래그는 신규 발급 API와 체험용 주문 접근 검사만 제어한다. 비활성 환경의 일반 주문은 Identity의 데모 scope를 조회하지 않는다. 반면 이미 발급된 workspace의 만료 worker와 Identity·Store 종료 adapter는 항상 활성화하여 설정을 끄거나 재시작해도 계정 credential, Session, 매장 주문 접수와 workspace를 종료한다.

Merchant discovery profile은 고객 탐색 포함 여부를 명시적으로 소유한다. 체험 매장은 생성 transaction에서 비노출로 표시하고 가까운 매장, 통합 검색(`openOnly=false` 포함), 즐겨찾기·최근 주문·추천 projection에서 제외한다. 체험 화면이 알고 있는 store ID의 직접 상세·메뉴 경로는 유지한다. Discovery가 Demo workspace 테이블을 직접 조인하지 않는다.

브라우저 귀속은 256-bit opaque HttpOnly Secure SameSite=Lax cookie의 SHA-256 hash로 식별한다. 변경 요청은 별도 Spring CSRF를 검사한다. cookie 원문/Session ID/password는 응답 JSON이나 로그에 기록하지 않는다. 동일 cookie/Idempotency-Key 요청은 한 결과를 반환한다. DB lock과 상한으로 동시 발급을 제한한다.

접근 기한은 발급 후 30분이며 갱신하지 않는다. 한 브라우저당 활성 공간은 하나다. 초기 운영 상한은 동시 20개, 하루 200개, 브라우저당 하루 5개로 제한한다. 상한은 서버 설정으로 더 낮출 수 있다. 종료/만료된 계정과 거래 증거는 재사용하거나 즉시 삭제하지 않는다. 별도 DB 보존/폐기 절차를 사용한다.

기본 샘플은 전용 매장 발행 포인트 4,500원을 사용해 픽업 슬롯 없는 IMMEDIATE BENEFIT_ONLY 승인으로 PAID를 만든다. 기본 주문과 재시작은 새 주문을 생성하며 기존 주문 전이를 되돌리지 않는다. 직접 주문도 픽업 시간을 선택하지 않는 기존 즉시 주문·Toss 테스트 결제 흐름이다. fake PG나 성공 응답으로 교체하지 않는다.

초기화는 각 owner의 제한된 신규 자원 생성 API를 호출한다. 실제 운영 권한을 위조하지 않으며 SYSTEM 감사와 같은 transaction으로 기록한다. 이 API에는 기존 자원의 변경이나 임의 사용자 선택 기능이 없다.

## Alternatives Considered
- 공유 계정: 간섭과 데이터 노출 때문에 제외.
- 브라우저 시뮬레이션: 실제 주문 API 검증 목적에 부합하지 않아 제외.
- 방문자별 DB: 운영 비용이 커서 전용 account/store scope로 대체.

## Rationale
기존 상품/주문 화면 및 상태 규칙을 재사용하면서 방문자별 접근 경계를 서버가 강제할 수 있다.

## Consequences
활성 환경에만 적용되는 인증 scope 검사와 발급 migration이 추가된다. 비활성 환경 주문에는 추가 Identity 조회가 없지만 만료 후보 scan은 계속된다. 만료 후에도 금융 원장을 보존하므로 전용 환경의 보존/폐기 운영이 필요하다. 주문의 실제 PK/reference/픽업 번호는 동적으로 표시한다.

## Verification
격리/탐색 비노출/비활성 주문 조회 0회/비활성 재시작 정리/만료/종료/중복/동시성/rollback/profile 보호와 오류 correlation 테스트, Storybook interaction/a11y, 실제 route 검증. 실행 증거는 ExecPlan에 기록한다.

## Metrics
`beanflow.demo.workspace`와 SYSTEM 감사로 생성/종료/오류를 관측한다. 원시 IP 또는 브라우저 cookie를 label로 사용하지 않는다.

## Revisit Conditions
발급 상한으로 체험 이탈이 발생하거나 더 긴 체험/영속 고객 전환이 필요할 때.

## Related Decisions
BR-03, BR-06, BR-58, ADR-094 및 방문자별 주문 처리 체험 ExecPlan.
