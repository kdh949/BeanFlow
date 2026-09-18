# ADR-136: 담당 상담원의 직접 처리와 과거 승인 기록 보존

- **Status:** Accepted
- **Date:** 2026-09-18

## Context

기존 Support는 고객·매장 인증, 매장 동의, 관리자·운영 승인 때문에 담당 상담원 단독 처리가 불가능하다.
기존 권한과 대상 경계를 보존하면서 외부 협조를 업무 선행조건에서 제거한다.

## Decision

신규 Support 업무는 `SUPPORT_DIRECT` 근거로 처리한다. 로그인, 업무별 persistent grant,
현재 담당자, 활성 상담과 활성 대상 연결, 주문 소유자 일치는 요청과 실행 시 다시 검증한다.
본인확인 세션과 승인자는 만들지 않는다. 실제 과거 세션·승인은 `LEGACY` 기록으로 보존한다.
주문 변경·해결·보상·고위험 프로필은 기존 등록→실행과 내용 hash/version binding을 재사용하며
같은 담당자가 실행한다. 승인 route는 NONE이다. 요청·수정 유효기간은 15분이다.
ACCEPTED 주문의 매장 동의를 제거하되 제조 이후는 기존 해결 흐름을 사용한다.
보상 risk band/hard cap/중복 지급 방지는 유지한다. 비용 근거에 SUPPORT_DECISION을 추가하며
비용 미확정은 임의 귀속하거나 성공으로 표시하지 않는다. 현재 로그인 전화 변경도 별도 인증을
요구하지 않으며 중복 제약·이력·구신 채널 알림을 유지한다.

목록은 마스킹하며 정보 보기 한 번으로 활성 grant 생성과 감사 후 원문 반환을 연결한다.
BASIC 10분/3회, SENSITIVE 5분/1회, BREAK_GLASS 2분/1회와 브라우저 60초 제거를 유지한다.
긴급 열람은 즉시 활성화하며 독립 사후 검토·보안 알림을 유지한다. 사후 검토는 다른 업무를 막지 않는다.
Audit 예약 commit→owner 복호화→현재 권한 재검사와 결과 commit→원문 반환 순서를 유지한다.
응답 유실은 원문 재전송으로 복구하지 않는다.

새 정책 버전을 발행한다. 기존 미실행 요청은 자동 승인·변환하지 않고 신규 실행에 409와 재작성
안내를 반환한다. 기존 완료 멱등 응답과 시작된 금융 후속 처리의 조회·복구는 보존한다.
신규 입력은 verificationSessionId 대신 subjectLinkId를 사용한다. 프로필은 기존 subjectId로
연결을 해석한다. 인증 생성/challenge/proof는 명시적으로 종료하고 과거 조회는 유지한다.
고객센터 전용 Verification Provider의 production startup 필수 조건을 종료한다.
기존 migration은 수정하지 않고 nullable FK, authorization_basis, 대상 snapshot과 직접 처리 시각을
새 migration으로 추가한다. 실제 승인이 없는 approver/approvedAt은 null이다.
관련 쓰기 중단 후 DB→서버→프런트를 함께 전환하며 혼합 버전 쓰기를 허용하지 않는다.

## Alternatives Considered

- 자동 VERIFIED/자동 승인 기록: 실제 사실과 다른 감사 기록이므로 제외.
- 새 직접 처리 엔진: 기존 금융·멱등성 경계를 중복하므로 제외.
- 승인 UI만 제거: 서버와 DB에서 계속 차단되므로 제외.

## Rationale

기존 owner 처리·잠금·복구를 유지하고 인가 근거만 현재 상담 담당자의 명시적인 결정으로 바꾼다.

## Consequences

API 입력과 DB 제약을 함께 전환해야 한다. 기존 미실행 요청은 같은 상담에서 재작성한다.
기존 권한을 확대하지 않으며 승인 분리 대신 직접 처리 actor·시각·사유를 감사한다.

## Verification

세션·승인자가 없는 동일 담당자의 모든 업무 실행, 권한/배정/연결/상태 거부,
동시 중복 실행·환불/지급 한도·제조 경쟁·Provider unknown, audit/decrypt 실패,
legacy 보존·409·금융 복구와 Provider 없는 시작을 검증한다. OpenAPI parity와 화면
interaction/a11y, 원문 제거·응답 유실을 검증한다.

## Metrics

직접 처리/거부/legacy 재작성/결과 불명과 감사 실패를 기존 PII-free 감사·상태로 관측한다.

## Revisit Conditions

권한 범위 또는 상담 배정 모델이 바뀌거나 직접 처리의 추가 제한이 제품 정책으로 확정될 때.

## Related Decisions

ADR-082·084·085·086·087·106의 신규 업무에 대한 인증·승인 조항을 대체한다.
이 ADR에 명시하지 않은 마스킹, owner 경계, 금융·감사·복구 조항은 유지한다.

## Amendment — 2026-09-18 review follow-up

SP-23 보완에 따라 직접 처리 요청의 실행자는 요청자와 같아야 한다. 담당자 교체는 기존
상담 배정과 새 요청 작성으로 처리한다. 이전 Operations 승인 요청은 결정 가능으로 표시하지
않고 조회·재작성 안내를 제공한다. 완료된 결정의 멱등 응답은 보존한다.
종료된 인증 쓰기는 사용하지 않는 본문·멱등키 검증을 제거해 410을 일관되게 반환한다.

위 Decision의 보상 hard cap 유지는 현재 누적 실행 한도에 적용한다.
`supportedAmountMaximumKrw` 초과를 평가·등록에서 별도 거부하지 않는 현재 동작과 위험은
SP-23 보완의 명시적 운영 결정으로 수용한다. 이 보완은 보상 계산이나 누적 한도를 바꾸지 않는다.
