# Frontend workflow coverage

2026-09-11의 실제 Controller/Runtime OpenAPI, 제품 정책, route와 Storybook을 대조한 구현 결과다.
최초 확인한 화면 결함 F01–F18과 누락 업무 M01–M17을 아래 PR의 수직 슬라이스로 연결했다.
최종 재대조에서 최근 주문 매장 직접 조회와 이미지 삭제 204 응답 처리도 보완했다.

## Findings and implemented workflows

| ID | 구현 결과 | PR |
|---|---|---|
| F01 | 날짜·요일과 픽업 구간 표시 | [#160](https://github.com/kdh949/BeanFlow/pull/160) |
| F02 | 등록된 판매 구성 조회·선택 | [#160](https://github.com/kdh949/BeanFlow/pull/160) |
| F03 | 공개 주문번호로 결제 재개 | [#160](https://github.com/kdh949/BeanFlow/pull/160) |
| F04 | 갱신 실패 시 과거 상태와 쓰기 차단 | [#160](https://github.com/kdh949/BeanFlow/pull/160) |
| F05 | 320px 검색/지우기 버튼 영역 분리 | [#160](https://github.com/kdh949/BeanFlow/pull/160) |
| F06 | 모바일 즐겨찾기 이름·주소·상태 재배치 | [#160](https://github.com/kdh949/BeanFlow/pull/160) |
| F07 | 공통 라벨·핵심 상태의 14px 이상 토큰 적용 | [#160](https://github.com/kdh949/BeanFlow/pull/160) |
| F08 | 서버 견적의 현재 메뉴·옵션 이름 사용 | [#160](https://github.com/kdh949/BeanFlow/pull/160) |
| F09 | 결제 만료 시점에 행동 갱신 | [#160](https://github.com/kdh949/BeanFlow/pull/160) |
| F10 | 취소·거절 타임라인의 잘못된 현재 강조 제거 | [#160](https://github.com/kdh949/BeanFlow/pull/160) |
| F11 | 요일별 실제 운영시간 표시 | [#160](https://github.com/kdh949/BeanFlow/pull/160) |
| F12 | 장바구니 signed URL 보관 제거·현재 이미지 조회 | [#160](https://github.com/kdh949/BeanFlow/pull/160) |
| F13 | 활성 주문 조회 갱신과 실패 상태 | [#160](https://github.com/kdh949/BeanFlow/pull/160) |
| F14 | 쿠폰 받기 진입 통합 | [#160](https://github.com/kdh949/BeanFlow/pull/160) |
| F15 | 내장 고객 문의 접수·인수·공개 대화 | [#167](https://github.com/kdh949/BeanFlow/pull/167) |
| F16 | 브랜드 이전/다음 페이지 연결 | [#162](https://github.com/kdh949/BeanFlow/pull/162) |
| F17 | 실제 Runtime OpenAPI와 생성 타입 동기화 | [#160](https://github.com/kdh949/BeanFlow/pull/160) |
| F18 | 역할별 lazy route 코드 분리 | [#160](https://github.com/kdh949/BeanFlow/pull/160) |
| M01 | 공개 소개·주소·길찾기·주간 운영시간 편집 | [#161](https://github.com/kdh949/BeanFlow/pull/161) |
| M02 | 픽업 슬롯 목록·상세·생성·수정 | [#161](https://github.com/kdh949/BeanFlow/pull/161) |
| M03 | 메뉴 표시 내용, 점주 및 운영 이미지 조회·교체·삭제 | [#161](https://github.com/kdh949/BeanFlow/pull/161) / [#168](https://github.com/kdh949/BeanFlow/pull/168) |
| M04 | 운영 매장 검색·생성·식별정보·지역 선택 | [#162](https://github.com/kdh949/BeanFlow/pull/162) |
| M05 | 브랜드 지정·해제 | [#162](https://github.com/kdh949/BeanFlow/pull/162) |
| M06 | 불변 정산 계약 이력·미래 구간 등록 | [#162](https://github.com/kdh949/BeanFlow/pull/162) |
| M07 | 계정 소속 추가·역할·철회·재활성화 | [#162](https://github.com/kdh949/BeanFlow/pull/162) |
| M08 | 점주 이의 상세·철회·새 증빙 재접수 | [#163](https://github.com/kdh949/BeanFlow/pull/163) |
| M09 | 운영 이의 목록·검토·판정 | [#163](https://github.com/kdh949/BeanFlow/pull/163) |
| M10 | 알림·publication 복구 조회·재시도·결과 확인 | [#163](https://github.com/kdh949/BeanFlow/pull/163) |
| M11 | 환불 대사·복구 제안·별도 승인·운영 환불·포인트 조정 | [#163](https://github.com/kdh949/BeanFlow/pull/163) |
| M12 | 공통·매장별 포인트 현재 정책과 이력 | [#162](https://github.com/kdh949/BeanFlow/pull/162) |
| M13 | 상담 목록·접수·배정·상태·내부 기록·대상 연결 | [#164](https://github.com/kdh949/BeanFlow/pull/164) |
| M14 | 주문 조치 평가·요청·수정·별도 승인·실행·점주 동의 | [#164](https://github.com/kdh949/BeanFlow/pull/164) |
| M15 | 수락 후 해결 계획·실행·대사·보상·알림 재시도 | [#165](https://github.com/kdh949/BeanFlow/pull/165) |
| M16 | 목적별 정보 정정·운영 심사·실행·알림 | [#166](https://github.com/kdh949/BeanFlow/pull/166) |
| M17 | 긴급 열람 요청·승인·만료·철회·사후 검토 | [#166](https://github.com/kdh949/BeanFlow/pull/166) |

최근 주문 매장 직접 조회는 `feature/frontend-recent-stores`의 `/app/recent-stores`와 내 정보 진입에서
BR-40/기존 API의 정렬·노출·최대 20개 상한을 따른다. 이미지 삭제 성공 표시는 #168에 포함된다.

## Runtime API coverage and intentional alternatives

현재 Runtime은 221개 path / 254개 operation이다. 정적 typed call 대조는 235개를 찾았고,
`auth/operationsSession.ts`의 실제 fetch 한 개를 별도로 확인해 직접 호출은 236개다.
호출 수는 전체 업무 완결성의 대체 지표가 아니므로 위 목록/선택/명령/결과 흐름을 따로 검증했다.

| 직접 호출하지 않는 계약 | 화면의 대응 경로 또는 범위 |
|---|---|
| 내부 Order ID 재주문·취소·결제 시도 (3개) | `/me/orders/{orderReference}`의 공개 주문번호 기반 명령 |
| 내부 Payment ID 환불 (점주/운영 2개) | 선택 매장과 공개 주문번호의 환불 preview/실행 |
| 내부 StoreOrder ID 상세·상태 변경 (2개) | `/stores/{storeId}/orders/{orderReference}` 상세/전이 |
| 내부 PointAccount ID 요약·원장 (2개) | `/me/points`, `/me/point-transactions` 고객 소유 조회 |
| 브랜드·쿠폰 캠페인 단건 조회 (2개) | 현재 목록이 반환한 해당 리소스로 편집·발행·중단/배너 관리 |
| 주문 단독 Support timeline (1개) | Case timeline에서 연결 주문의 오너 사실/후속 업무 조회 |
| 보상·정보 정정 단건 조회 (2개) | 현재 권한·허용 행동을 포함하는 `/workflow` 조회 |
| 저장 결제수단 목록/등록/해제/기본 지정 (4개) | ADR-080의 P0 제외. 활성 checkout은 1회성 결제창 |

Target에만 있는 일반 실패업무 집계, 신규 Analytics/정산/감사 조회 등은 구현된 API로 계산하지 않았다.
저장 결제수단, 재고 도메인 부활, 은행 지급 실행과 외부 메시지 전달은 기존 승인 정책을 유지한다.

## Design and failure behavior

190개 editable token 및 기존 canonical 컴포넌트를 사용한다. 점주 Session/CSRF, 운영 Bearer와
고객 Session의 호출 경계를 유지하며 이미지 화면만 공유한다. 숨겨진 mock/fallback이나 임시 담당자를
제품 상태로 만들지 않는다. 조회 실패는 stale success/empty/0으로 대체하지 않으며 명령 응답 유실,
UNKNOWN/RECONCILING/MANUAL_REVIEW, 부분 완료와 별도 승인 조건을 구분한다.

고객 문의는 V82의 별도 공개 대화와 실제 담당 Case를 원자적으로 연결한다. 내부 노트와 인증 자료는
공개 응답에 포함하지 않는다. 보존 정책 snapshot과 90일 멱등 metadata 정리는 구현했으며 향후 S110
보존 실행 surface와 외부 전달을 완료했다고 주장하지 않는다.

## Verification boundary

제품/Storybook 빌드, typecheck, 디자인 검사, frontend unit 233개와 boundary/copy 21개,
PostgreSQL 계약·권한·동시성·실패 테스트, Runtime parity, Modulith 및 문서 검증을 실행했다.
최종 전체 MCP/정적 Docs 결과는 [실행 계획](../exec-plans/active/frontend-workflow-completion.md)에 기록한다.
320px 검색과 390px 주요 고객/점주/운영/상담 화면은 DOM 치수와 실제 렌더링을 확인했다.
화면 상호작용 검증은 Storybook MSW 경계이며 실제 PG 결제·운영 이미지·외부 알림 발송 검증과 다르다.

PR은 #160 → #161 → #162 → #163 → #164 → #165 → #166 → #167 → #168 → 최근 매장 순서다.
원격 CI는 각 PR의 실제 head checks에서 확인하며 merge/deploy는 별도 단계다.
