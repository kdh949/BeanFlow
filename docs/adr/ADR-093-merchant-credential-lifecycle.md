# ADR-093: 점주 계정 자격증명 lifecycle과 최초 비밀번호 강제 변경

- **Status:** Accepted
- **Date:** 2026-08-11
- **Implementation owner:** [Merchant account and initial password](../exec-plans/active/productization-40-merchant-account-and-initial-password.md)

## Context

점주는 스스로 가입하지 않는다. 매장 입점 심사는 이번 범위의 Non-goal이므로
([Design Contract Conflicts C-8](../product/design-contract-conflicts.md)), 점주 계정은 운영팀이
발급한다. 발급된 계정이 임시 비밀번호를 그대로 쓰면 계정 탈취가 곧 매장 주문·환불·정산 접근이 된다.

현재 저장소에는 점주 자격증명이 없고 `identity_store_membership`만 있다. 이 membership은
`actorId`가 이미 인증됐다고 가정한다. 그 `actorId`를 만드는 주체와 lifecycle이 정의되지 않았다.

## Decision

점주 계정은 `MerchantAccount` Aggregate가 소유하고 다음 lifecycle을 갖는다.

- 로그인 ID는 [BR-34](../product/business-policy-decisions.md)의 고객과 같은 canonical 규칙을
  사용한다. 운영자 발급 입력의 앞뒤 공백을 제거하고 ASCII 대문자를 소문자로 바꾼 뒤 검증하며,
  점주 namespace 안에서 유일하다. 고객 계정의 같은 문자열과는 충돌하지 않는다.

```text
운영자 발급 → INITIAL_PASSWORD → (비밀번호 변경) → ACTIVE
                    │
                    └── 임시 비밀번호 만료 → EXPIRED

모든 lifecycle 상태
  └── 로그인 실패 한도 도달 → lockedUntil 시간 제한 overlay
```

### 상태와 허용 동작

| 상태 | 로그인 | 비밀번호 변경 API | 그 밖의 매장 API |
|---|---|---|---|
| `INITIAL_PASSWORD` | 가능 | 가능 | **전부 403** |
| `ACTIVE` | 가능 | 가능 | membership 범위에서 가능 |
| `EXPIRED` | 불가 | 불가 | 불가 |

`now < lockedUntil`이면 위 lifecycle과 무관하게 로그인·비밀번호 변경·매장 API가 모두 불가하다.

- 임시 비밀번호는 발급 시각 기준 유효기간을 갖는다. 만료된 임시 비밀번호로는 로그인할 수 없고
  운영자 재발급이 필요하다.
- `INITIAL_PASSWORD` 상태의 Session은 유효한 Session이지만 **비밀번호 변경 endpoint와
  `/api/v1/merchant/me`
  외에는 모두 403**이다. 이 판정은 Controller 조건문이 아니라 Merchant Chain의 인가 규칙과
  Application Service 양쪽에서 수행한다.
- 비밀번호는 Hash만 저장한다. 임시 비밀번호도 평문으로 저장하거나 로그에 남기지 않는다.
- 점주 계정 발급과 초기화는 [BR-46](../product/business-policy-decisions.md)의 Operations Web 명령이
  소유한다. 서버가 생성한 임시 비밀번호는 최초 성공 응답에서 한 번만 표시하고 이후 조회할 수 없다.
  발급은 MerchantAccount, 최초 ACTIVE StoreMembership, attempt 정리와 AuditRecord를 한 transaction에
  commit한다.
- 로그인 실패는 계정별·IP별로 누적하고 임계값에 도달하면 lifecycle을 바꾸지 않은 채
  `lockedUntil`을 설정한다. 만료 뒤에는 `INITIAL_PASSWORD` 또는 `ACTIVE`였던 상태로 정상 로그인을
  계속한다. 운영자는 만료 전에 조기 해제할 수 있다. 조기 해제와 비밀번호 초기화는 account의
  `lockedUntil`과 같은 점주 LOGIN_ID attempt 차단을 한 transaction에서 함께 지우며 감사 기록을
  남긴다.
- 계정 생성, 비밀번호 초기화, 잠금 해제는 모두 `AuditRecord`를 남긴다
  ([ADR-022](ADR-022-audit-record.md)). 감사에는 대상 계정 ID, 실행 운영자, 사유가 들어가고
  비밀번호 값과 Hash는 들어가지 않는다.
- **2026-08-13 self-change audit amendment:** 점주 자신의 비밀번호 변경도 자격증명 소유권 전환을
  증명하는 `AuditRecord`를 같은 계정 transaction에 남긴다. 계정 범위 행위이므로 특정 매장의
  `OWNER`/`STAFF` membership을 추론하지 않고 `MERCHANT` actor type을 사용한다. Audit에는 계정 상태와
  credential version 전이만 요약하고 현재·새 비밀번호와 Hash를 남기지 않는다.
- 비밀번호 변경·운영자 초기화·잠금은 계정 transaction에서 `credentialVersion`을 증가시킨다.
  비밀번호 변경 성공 뒤 새 Session은 증가한 version으로 발급하며, 기존 Session은 행 삭제 성공 여부와
  무관하게 version 불일치로 즉시 401이다([ADR-094](ADR-094-browser-session-security.md)).
- 계정의 매장 접근 범위는 여전히 `StoreMembership`이 소유한다
  ([ADR-027](ADR-027-store-membership-authorization.md)). 계정 상태가 `ACTIVE`라고 해서 매장 접근
  권한이 생기지 않는다.

### 이번 범위에서 만들지 않는 것

- 자체 회원가입
- `MANAGER` 역할과 4자리 PIN 재확인(P1, [C-6](../product/design-contract-conflicts.md))
- 비밀번호 재설정 이메일·SMS 발송

## Alternatives Considered

### 1. 임시 비밀번호 없이 운영자가 최종 비밀번호를 설정

- 장점: 상태가 하나 줄어든다.
- 단점: 운영자가 점주의 최종 비밀번호를 알게 된다. 감사 관점에서 점주 행위와 운영자 행위를
  분리할 수 없다.

### 2. 최초 로그인 시 경고만 표시하고 기능은 허용

- 장점: 점주가 즉시 매장을 운영할 수 있다.
- 단점: 임시 비밀번호가 유출된 상태로 환불·정산 접근이 열린다. 경고는 통제가 아니다.

### 3. 일회용 로그인 링크 발급

- 장점: 임시 비밀번호 자체가 없다.
- 단점: 발송 채널(이메일·SMS) 의존성이 생긴다. 현재 저장소에 검증된 발송 채널이 없어
  없는 의존성을 가정하게 된다.

## Rationale

임시 비밀번호는 "운영자가 만든 접근"과 "점주가 소유한 접근"을 시간으로 분리하는 가장 단순한
장치다. 강제 변경 전에 매장 기능을 전부 막으면, 임시 비밀번호가 유출되더라도 공격자가 얻는 것은
비밀번호 변경 화면 하나다. 상태 하나와 인가 규칙 하나로 위험을 크게 줄인다.

## Consequences

- 점주 API 테스트는 계정 생성 → 최초 로그인 → 비밀번호 변경까지 거쳐야 한다. 테스트 fixture가 길어진다.
- 운영자 계정 발급·초기화·조기 해제 화면과 `MERCHANT_CREDENTIAL_MANAGE` permission이 필요하다.
  임시 비밀번호 응답을 잃으면 이전 값을 복구하지 않고 새 초기화 명령으로 수렴한다.
- 잠금 임계값, 비밀번호 정책과 임시 비밀번호 유효기간은
  [BR-35](../product/business-policy-decisions.md)가 고정한다. 구현이 다른 기본값으로 완화하지 않는다.

## Verification

- `INITIAL_PASSWORD` Session으로 매장 주문 목록·상태 전이·환불·정산 요청이 모두 403인지 검증한다.
- 만료된 임시 비밀번호 로그인이 실패하는지 검증한다.
- 실패 누적이 임계값에서 `lockedUntil`을 설정하고, 잠금 중 올바른 비밀번호도 실패하는지 검증한다.
- 잠금 만료 뒤 `INITIAL_PASSWORD`와 `ACTIVE`가 각각 원래 lifecycle로 로그인하고, 잠금 전에 발급한
  Session은 `credentialVersion` 불일치로 계속 401인지 검증한다.
- 비밀번호 변경 후 이전 Session이 무효화되는지 검증한다.
- 이전 Session 행 삭제 실패를 주입해도 `credentialVersion` 불일치로 재사용이 401인지 검증한다.
- 계정 생성·초기화·잠금 해제가 `AuditRecord`를 남기고 비밀번호를 저장하지 않는지 검증한다.
- 점주 비밀번호 변경 Audit가 `MERCHANT` actor로 원자 저장되고 Audit 실패 시 자격증명이 바뀌지 않는지
  검증한다.
- 발급과 최초 StoreMembership 중 하나라도 실패하면 둘 다 남지 않고, 임시 비밀번호가 최초 성공
  응답 외 DB·Audit·log·frontend storage에 남지 않는지 검증한다.
- 동시 로그인 실패 요청에서 실패 카운터가 유실·중복되지 않는지 PostgreSQL Testcontainers로 검증한다.
- 운영자 발급에서 5~32자·허용 문자·첫끝 문자·ASCII 대소문자 canonicalization과 점주 namespace
  중복을 검증한다.

## Metrics

- 최초 로그인 후 비밀번호 변경까지의 소요 시간 분포
- 임시 비밀번호 만료율과 재발급 수
- 계정 잠금 발생 수와 해제 수
- `INITIAL_PASSWORD` 상태에서 차단된 요청 수

## Revisit Conditions

- 점주 자체 가입 또는 입점 심사가 범위에 들어올 때
- `MANAGER` 역할과 명령 단위 step-up이 필요할 때
- 검증된 이메일·SMS 발송 채널이 생겨 비밀번호 재설정을 자동화할 수 있을 때

## Related Decisions

- [ADR-092](ADR-092-hybrid-authentication.md)
- [ADR-094](ADR-094-browser-session-security.md)
- [ADR-027](ADR-027-store-membership-authorization.md)
- [ADR-022](ADR-022-audit-record.md)
