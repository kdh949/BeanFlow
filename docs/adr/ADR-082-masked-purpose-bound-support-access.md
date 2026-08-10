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
