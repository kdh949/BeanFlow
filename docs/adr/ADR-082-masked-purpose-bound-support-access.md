# ADR-082: 기본 마스킹, staged verification과 purpose-bound PII access

- **Status:** Accepted
- **Date:** 2026-08-10

## Context

상담원은 exact PII로 대상을 찾을 수 있어야 하지만 검색, 확인과 raw reveal을 하나의 권한으로 만들면 내부 오남용과 브라우저 잔류 위험이 커진다.

## Decision

모든 결과는 기본 마스킹한다. `UNVERIFIED/BASIC_VERIFIED/ENHANCED_VERIFIED`는 Case+Subject+Purpose-bound session이고 `BREAK_GLASS`는 별도 path다. Raw reveal은 operator+Case+Subject+field+reason+expiry/count-bound DataAccessGrant와 원문 반환 전 성공한 Audit을 요구한다. Operations 조사도 별도 grant 없이는 마스킹한다. Break glass는 최소 필드·긴급 사유·승인/사후검토·보안 통지를 요구한다.

## Alternatives Considered

- Role만으로 전체 profile: 과권한이라 기각.
- Verification 성공=raw access: 목적·필드 분리가 없어 기각.
- Break glass를 verification level로 표현: 정상 권한을 우회해 기각.

## Rationale

검색, 인증 evidence, 필드 공개와 변경 권한을 독립적으로 제한하고 감사 실패에 fail-closed한다.

## Consequences

Grant/Audit latency와 운영 검토 비용이 늘며 UI는 reveal expiry를 처리해야 한다. PAN/CVC/password/OTP/token/key 등 R4는 grant로도 공개하지 않는다.

## Verification

Authorization matrix, other Case/Subject reuse, BASIC-for-ENHANCED, Audit failure, break-glass review와 browser residue tests.

## Metrics

Reveal/denied/break-glass/search counts와 Audit latency; PII는 label에 넣지 않는다.

## Revisit Conditions

사칭·내부 오남용 사고, verification 포기율 또는 법률/보안 검토 변경.

## Related Decisions

ADR-009, ADR-020, ADR-021, ADR-022, ADR-069.

## 화면에서의 긴급 열람 관리 조회 (2026-09-11)

긴급 열람 요청은 원문을 포함하지 않는 별도 workflow GET으로 현재 요청·만료·가능한 명령을 조회한다. 현재 긴급 열람 요청 권한이 있는 요청자, 기존 승인 권한을 가진 별도 승인자, 사후 검토 단계의 독립된 개인정보 검토자만 해당 메타데이터에 접근한다. 일반 상담 열람 권한만으로 접근을 확대하지 않는다.

승인과 원문 열람은 활성 Case, 현재 배정과 subject link 및 각 명령 권한을 기존 쓰기 경계에서 재검증한다. 종료된 Case에서도 이미 소비된 열람의 독립 사후 검토는 가능하다. 원문은 GET·멱등 응답·URL·브라우저 저장소에 넣지 않는다. 화면은 명시적인 1회 열람 이후 60초, 승인 만료, 화면 이탈, 권한 또는 배정 회수 중 가장 먼저 발생한 경계에 지우며, 표시 중에는 현재 조회 권한을 다시 확인한다. 조회 실패 시 원문을 즉시 지운다. 열람 응답 유실은 원문 재전송 없이 현재 요청 상태를 조회하고 사후 확인으로 처리한다.
