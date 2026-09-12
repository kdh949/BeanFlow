# ADR-086: versioned risk compensation과 goodwill source 분리

- **Status:** Accepted
- **Date:** 2026-08-10

## Context

기존 refund, benefit restoration과 audited PointAdjustment는 고객 불편 goodwill과 목적·승인·비용 책임이 다르다.

## Decision

REFUND, BENEFIT_RESTORATION, LEDGER_CORRECTION, GOODWILL_COMPENSATION source를 분리한다. Support compensation은 immutable PolicyVersion, exact request, one POINT-or-COUPON benefit, rolling buckets, duplicate terminal key와 cost responsibility snapshot을 가진다. Point는 new Lot/append-only transaction, Coupon은 approved immutable template를 사용한다. Unknown responsibility는 Store/Platform fallback하지 않는다. HIGH/EXCEPTIONAL은 Operations investigation 후 agent execution이다.

### S90 initial runtime amendment (2026-08-12)

Initial immutable policy version은 3,000/10,000/30,000원 LOW/MEDIUM/HIGH 경계와 다음 execution hard cap을
snapshot한다: CUSTOMER 30일 30,000원, ORDER 30일 30,000원, INCIDENT 30일 30,000원과 lifetime terminal 1회,
ACTOR 1일 100,000원, STORE 1일 300,000원. 이는 측정 optimum이 아닌 SP-21 initial assumption이다. 변경은 기존
version을 수정하지 않고 새 version과 head CAS로만 적용한다.

LOW는 BASIC+agent, MEDIUM은 BASIC+Support Manager, HIGH와 EXCEPTIONAL은 ENHANCED+Operations investigation
route다. Operations는 exact request를 APPROVE/DENY/RETURN/ESCALATE하고 benefit을 수정·발급하지 않는다. 기존 S60
`SupportActionRequest` revision과 required Operations callback을 `GOODWILL_COMPENSATION` typed target에 재사용한다.
승인자는 executor가 될 수 없으며 execution은 current assignment, permission, verification, exact revision/policy/request
version을 다시 검사한다.

Cost responsibility는 `PLATFORM | STORE | SHARED | UNDETERMINED`다. STORE/SHARED는 closed evidence basis와 digest가
필수이고 SHARED bps 합은 10,000이다. `UNDETERMINED`는 조사는 가능하지만 executable cost owner가 아니므로 Operations
approval을 Platform/Store fallback으로 해석하지 않는다. exact responsibility의 새 request 없이는 발급하지 않는다.

Rolling scope guard는 CUSTOMER→ORDER→INCIDENT→ACTOR→STORE canonical 순서로 잠그고 실제 rolling window의 immutable
terminal consumption을 합산한다. execution transaction은 limit recheck, incident terminal unique, owner-local issuance,
Support terminal result, S60 one-time consumption과 Audit를 함께 commit한다. Support는 owner table을 직접 쓰지 않고
Loyalty/Promotion public Application API를 호출하며 외부 Provider 호출은 없다.

POINT는 `SUPPORT_COMPENSATION` PointTransaction과 새 PointLot으로 발급한다. SHARED는 Platform/Store 별도 funding
Lot/transaction leg를 만들고 합계가 request amount와 일치한다. COUPON은 Promotion-owned immutable fixed-KRW template로
발급하며 자유 조건을 받지 않는다. issuance cost snapshot은 future redemption Order의 existing settlement-input 경계가
사용하고 issuance 시점에는 SettlementItem/Adjustment를 만들지 않는다. 알림은 발급 commit 뒤 별도 durable transaction이며
실패가 benefit을 rollback하지 않는다.

### 상담 화면의 보상 검토 및 템플릿 조회 (2026-09-11)

현재 Case와 Customer/Order 연결이 유효한 범위에서 보상 workflow는 혜택·비용 책임·분담 비율·증빙
해시·정책·대상 버전과 현재 가능한 명령을 제공한다. 요청자/현재 실행 담당자/기존 승인자 외에,
현재 대기 중인 exact 승인안의 별도 승인 권한자도 이 비개인정보 검토 자료를 조회할 수 있다.
본인확인 원문과 고객 개인정보는 반환하지 않으며 조회만으로 승인 또는 발급하지 않는다.
기존 보상 단건 조회의 열람 범위는 변경하지 않는다.

쿠폰 목록은 Promotion이 소유한 불변 템플릿의 ID·정액·유효 일수·최소 사용 금액만 커서로 조회한다.
상담 보상 요청 권한을 검사하고 1~50개로 제한한다. 선택 후에도 평가·발급 시 템플릿 조건을 재검증한다.
수정 요청을 받은 보상은 같은 사고 ID로 새 불변 요청을 평가·생성하며 종전 승인안을 재사용하지 않는다.

보상 업무 조회는 쿠폰의 불변 할인액·유효기간·최소 사용 금액을 승인자에게 함께 제공한다. BR-51의 `NOTIFICATION_SKIPPED`를 계약에도 포함하여 수신 거부에 따른 미발송을 전달 성공이나 실패로 표현하지 않는다.

## Alternatives Considered

- PointAdjustment/restoration 재사용: audit/source 의미가 잘못되어 기각.
- mutable current table로 재계산: 과거 request가 변해 기각.
- agent 자유 쿠폰/비용 입력: 오남용·정산 불일치로 기각.

## Rationale

금전적 부수효과, 정책 version과 비용 책임을 재현 가능하게 한다.

## Consequences

Initial amounts(3k/10k/30k, 30-day 10k)은 측정 optimum이 아닌 policy assumptions다. buckets/owner APIs/schema가 필요하다.

## Verification

Band boundaries, version immutability, duplicate/rolling concurrency, Lot/issuance/Audit atomicity, unknown cost and notification failure.

## Metrics

Band/benefit/cost responsibility별 evaluated/issued, duplicate/limit conflict. 개인 식별 label 금지.

## Revisit Conditions

실제 distribution, abuse, cost or resolution outcomes가 초기 policy 재조정을 요구할 때.

## Related Decisions

ADR-011, ADR-028, ADR-041~043, ADR-049, ADR-066.

### 상담 사고 등록과 기존 사고 선택 (2026-09-11)

Support는 화면에서 새 사고를 등록할 때 안정된 식별자를 생성한다. 등록은 현재 담당한 active Case,
SUPPORT_CASE_READ/COMPENSATION_REQUEST, 유효한 고객 본인확인과 고객·주문 연결을 요구한다.
사고는 고객과 선택한 주문(또는 주문 없음)에 불변으로 묶인다. 분류는 접수된 Case의 실제 분류,
발생 시각은 상담원이 명시한 사실을 사용하며 미래 시각은 거부한다. 자유 개인정보·사고 설명을
새 테이블에 저장하지 않는다. 화면은 발생/등록 시각·분류·기지급 여부로 사고를 구분한다.

목록은 현재 검증된 고객과 같은 주문 범위의 등록 및 기존 보상 요청에 기록된 사고를 함께 제공한다.
기존 사고의 발생 시각은 추정하지 않고 미기록으로 표시한다. 다른 Case의 원문·담당자·본인확인 정보를
반환하지 않는다. 커서는 actor/Case/session/customer/order에 바인딩한다. 등록된 사고를 보상 평가·생성·
실행에서 사용할 때 고객/주문 binding을 다시 확인한다. 기존 API 호출자의 외부 사고 참조 호환성은
유지하되 화면에서는 확인된 목록 또는 등록 응답에서만 사고를 선택한다. 종전 사고 ID를 바꾸거나
기존 terminal 및 rolling consumption을 재분류하지 않는다.

등록은 actor+idempotency key로 직렬화하며 같은 payload는 같은 사고를 재생하고 다른 payload는 409다.
사고/명령 identity/PII-free Audit는 한 transaction에서 commit한다. 등록은 지급이 아니며 보상 명령의
별도 승인, 현재 버전, 사고별 lifetime terminal unique와 rolling 한도를 그대로 적용한다.
