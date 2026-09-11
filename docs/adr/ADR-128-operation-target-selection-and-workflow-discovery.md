# ADR-128: 운영 대상 선택과 상담 요청 탐색

- **Status:** Accepted
- **Date:** 2026-09-11
- **Implementation owner:** [내부 ID 입력 제거](../exec-plans/active/internal-identifier-workflow-selection.md)

## Context

관리 API를 호출하는 화면이 있어도 내부 ID를 사람이 먼저 구해 입력해야 하면 업무가 완결되지 않는다.
#170은 고객 선택 포인트 업무를 해결했다. 매장/주문/담당자/요청/사고의 탐색은 별도 보완이 필요하다.

## Decision

- BR-57에 따라 업무 대상을 실제 검색 결과 또는 현재 권한으로 조회한 목록에서 선택한다.
- 기존 매장 이름 검색과 명령 API를 재사용한다. 공개 주문번호는 Ordering이 내부 주문에 연결한다.
- 목록은 owner-local DTO projection이다. 다른 Aggregate graph 확장이나 무인가 cross-table 검색을 추가하지 않는다.
- 요청 목록은 발견 수단이며 상세/승인/실행은 현재 권한·담당자·대상·버전·본인확인과 기존 분리 승인을 재검증한다.
- 검색 실패는 empty, 과거 결과 또는 임의 표시 이름으로 바꾸지 않는다. 선택은 route memory에 두고
  변경/화면 이탈 때 제거한다. 미확인 금융 명령은 대상·payload·동일 key를 유지한다.
- ID-only 입구는 목록/선택으로 교체한다. 기존 상세 공유 주소는 유지한다. 생성 직후에는 서버 결과의
  식별자를 내부적으로 연결한다. 신규 사고 식별자를 화면 재렌더링마다 생성하여 중복 지급 제한을 우회하지 않는다.
- 담당자 표시 정보와 비용/외부 참조는 확인된 출처를 사용한다. 실제 자료 없는 별칭/기본 주체를 생성하지 않는다.
- 각 기능의 구체 조회/등록 계약은 해당 owner ADR과 함께 이 문서에 추가하며 contract-first로 검증한다.

### Store and membership labels

매장 이름은 Merchant의 현재 공개 매장 프로필에서, 점주·직원 표시 이름과 로그인 아이디는 Identity의
현재 MerchantAccount에서 읽는다. 목록의 현재 grant와 감사 경계 안에서 최소 표시 정보만 조회하며
클라이언트의 행별 HTTP 요청 대신 owner port의 일괄 조회를 사용한다. 매장 정책 목록에는 `scopeName`,
계정 조회의 소속에는 `storeName`, 소속 조회에는 `accountDisplayName`/`accountLoginId`를 추가한다.
이 필드는 조회용이며 기존 명령의 멱등 응답·감사 snapshot에 표시 이름을 소급 저장하지 않는다.
등록된 표시 정보가 사라진 경우 조회 실패로 드러내며 UUID 별칭이나 이전 이름으로 대체하지 않는다.
소속 추가는 기존 로그인 아이디 정확 조회로 통일하고 수동 account UUID 경로를 제거한다.

### Order and repair discovery

`GET /operations/order-compensations/{orderReference}`는 `ORDER_COMPENSATION_READ`와 기존 조회 사유를
확인한 뒤 Ordering이 공개 주문번호를 정규화·정확 조회한다. 기존 보상 조사와 감사 transaction 안에서
주문 표시 정보와 후속 처리 상태를 반환한다. 내부 UUID 경로는 호환성을 위해 유지한다. Operations가 소유하는
outbound query port를 Ordering이 구현하여 기존 모듈 의존 방향을 유지한다.
주문번호·주문 시점 매장명·주문 상태·생성 시각을 표시하고 고객/Provider 정보는 제외한다.
권한 있는 주문 조회의 내부 주문/매장 ID는 명령 연결용으로만 전달하며 화면의 입력이나 이름으로 쓰지 않는다.

`GET /operations/payment-setup-recovery-cases`와 `GET /operations/reprocessing-repair-proposals`는
`PAYMENT_CANCELLATION_SETUP_REPAIR`가 있는 운영자가 대상과 현재 상태를 선택하기 위한 목록이다.
커서는 actor, 상태 필터와 선택한 Case에 바인딩한다. owner-local 복구 조회와 Ordering의 일괄 표시
projection을 사용하며 상세·제안·판정은 기존 grant와 safe guard를 다시 검증한다. 목록 조회는 만료 처리나
복구를 실행하지 않는다. 유효기간이 지난 대기 항목은 현재 시각 기준 만료 안내와 함께 실행을 막는다.
제안자/판정자의 이름은 담당자 디렉터리 단계에서 연결하고 이 단계에서는 본인 여부와 역할을 구분한다.
불명 명령 응답에서는 대상·payload·동일 key를 고정하고 입력을 바꿔 새 요청을 만들지 않는다.

### Manual sequential migration scope

이번 작업은 #170의 구현 제외를 명시한 수동 후속 요청이다. 정확한 기준은 #170
`716f9421c19bce255cd26cacc7f1a6dac095a61a`이며 최신 CI 통과를 확인했다. main/origin/main은 V81,
직렬 부모 #167은 V82, #170은 V83이고 열린 다른 PR #125에는 DDL이 없다. 각 writer의 구현이 끝난
동일 frontend 후속 stack에서 ADR-127 lane을 이어받는다. 하나의 milestone만 migration을 작성하고
검증된 head 뒤에 다음 milestone을 시작한다. 기존 migration/다른 checkout/공유 history는 수정하지 않는다.
이 요청의 최종 PR merge까지 동일 lane을 유지하며 다른 writer가 나타나면 inventory를 재검증한다.
이는 자동 실행의 main-first 규칙이나 unrelated schema writer 병렬 허용을 변경하지 않는다.

## Alternatives Considered

ID 설명문은 대상을 찾는 업무를 남긴다. 브라우저 영구 ID 목록은 stale 권한/대상 위험이 있다.
범용 DB 탐색기는 소유권·개인정보 범위를 과도하게 넓힌다. 업무별 최소 목록/선택이 기존 경계를 보존한다.

## Consequences

목록과 표시 projection이 추가되며 권한/감사 실패도 명시적으로 처리해야 한다. 기존 금전적 쓰기 경로,
비용 snapshot, 사고별 terminal unique와 순차 승인은 변하지 않는다.

## Verification

선택부터 명령 결과까지 Storybook interaction/a11y, 현재 권한과 교차 객체/커서, late response와
불명 명령, 실제 PostgreSQL 계약/rollback/중복/동시성, runtime parity와 Modulith를 검증한다.

## Revisit Conditions

외부 인사/참조 관리 연동, 데이터 대량 탐색, 새로운 개인정보 조회 범위 또는 금융 정책 변경이 필요할 때.
