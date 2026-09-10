# ADR-126: 고객 문의 접수와 공개 답변

- **Status:** Accepted
- **Date:** 2026-09-11

## Context

고객 도움말에는 문의를 시작할 방법이 없고 기존 SupportCase는 실제 담당자를 필수로 가진다.
제품 요구는 외부 문의 채널 대신 기존 상담 시스템에서 고객 문의를 처리하는 것이다.

## Decision

- Support가 CustomerInquiry 접수 Aggregate와 append-only 공개 메시지를 소유한다. 로그인 고객만
  자기 문의를 작성·조회한다. 고객 소유권은 인증 actor로 정하며 요청 body의 고객 ID를 받지 않는다.
- 고객 문의는 `RECEIVED`로 시작한다. `SUPPORT_CASE_READ`는 접수함을 조회하고
  `SUPPORT_CASE_WRITE`는 접수 건을 인수한다. 인수 transaction은 실제 인수자를 담당자로 하는
  SupportCase, 고객/주문 subject link, Inquiry binding, 멱등 기록, Audit을 함께 commit한다.
  한 Inquiry는 한 Case에만 연결되며 고객의 주문 연결은 Ordering 공개 port에서 소유권을 확인한다.
- 연결 후 상태는 Case의 현재 `OPEN/IN_PROGRESS/WAITING/RESOLVED/CLOSED`를 투영한다.
  문의 접수·답변은 환불·정정의 성공을 의미하지 않는다. 기존 Case 상태 전이는 변경하지 않는다.
- 고객과 현재 담당자만 공개 메시지를 추가한다. `RESOLVED/CLOSED`에는 새 메시지를 추가하지 않고
  고객이 새 문의를 접수한다. Case 변경과 경합하는 답변은 Case lock, Inquiry lock 순으로 직렬화한다.
  공개 메시지는 별도 테이블/DTO에 저장하며 내부 Note, Interaction, 본인 확인 자료, raw PII를 섞지 않는다.
- 본문은 2,000자, 제목은 100자로 제한한다. 기존 SupportContentPolicy의 민감 값 거부를 적용한다.
  본문에만 CRLF를 LF로 정규화한 줄바꿈을 허용하고 검사는 줄바꿈을 공백으로 바꾼 값에도 적용한다.
  입력 내용을 오류·로그·Audit에 반영하지 않는다. 개인정보 정정과 본인 확인은 기존 보호 workflow에서 한다.
- Inquiry/공개 메시지는 SUPPORT_CASE retention version을 접수 시 snapshot한다. 연결 Case 종료부터
  3년의 기존 정책을 적용하고 미인수/미종료 문의는 진행 중 기록으로 보존한다. 메시지는 문의의 자식이며
  문의 삭제 시에만 함께 제거한다. Case FK는 삭제를 제한해 공개 기록이 고아가 되지 않게 한다.
- 쓰기는 actor/operation/key로 멱등 처리한다. 같은 key의 다른 payload는 409이며, 90일 replay 원장은
  본문 대신 결과 식별자와 digest만 저장한다. 응답 유실 후 같은 key로 확인하며 새 문의/답변을 추측하지 않는다.
- 외부 메시지·메일 발송과 새 production dependency는 추가하지 않는다. 고객은 앱 문의함에서 답변을 읽는다.

## Alternatives Considered

외부 링크는 요구를 충족하지 않는다. 시스템 담당자로 Case를 즉시 생성하면 실제 할당 상태를 왜곡한다.
기존 내부 Note 공개는 정보 분리 경계를 무너뜨린다. 별도 접수와 공개 대화 모델을 선택한다.

## Consequences

접수함과 추가 스키마가 필요하다. 고객 인증이 필요한 내장 채널이며 익명 계정 복구는 지원하지 않는다.
네트워크 실패는 오류로 표시하고 저장 성공/업무 완료로 바꾸지 않는다.

## Verification

PostgreSQL 소유권·동시 인수·멱등 replay/conflict·Audit rollback·종료 경합·비공개 자료 분리 테스트,
Runtime OpenAPI parity, Modulith/ArchUnit, Storybook interaction/a11y와 모바일 overflow를 확인한다.

## Metrics

기존 HTTP 오류와 correlation ID, PII-free Audit의 CUSTOMER_INQUIRY_CREATE,
CUSTOMER_INQUIRY_MESSAGE, SUPPORT_INQUIRY_CLAIM, SUPPORT_INQUIRY_MESSAGE로 접수·처리를 추적한다.

## Revisit Conditions

익명 문의, 첨부파일, 외부 전달, 자동 배정 또는 다른 보존 기간이 필요할 때 별도 정책을 결정한다.

## Related Decisions

BR-55, ADR-072, ADR-089, ADR-090, SupportCase Policy.
