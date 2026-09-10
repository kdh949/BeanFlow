# 성능 드라이버의 Toss 환불 멱등 계약 PR 분리

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** —
> **Completed-At:** `2026-09-08`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

앱이 생성한 콜론 포함 환불 멱등키를 드라이버가 거절해 환불 UNKNOWN과 반복 조회를 만들던 계약 불일치를 해결한다.

## Current State

통합 구현과 배포에서 검증한 이 변경을 별도 PR로 추출한다. 다른 성능 수정의 코드를 포함하지 않는다.

## Definitions

append는 새 기록 추가, threshold는 시작한 요청의 성공뿐 아니라 목표 부하 미시작과 지연을 포함한 수용 조건이다.

## Scope

### In Scope

`refund:rejection:<eventId>`를 포함하는 공백 없는 printable ASCII 1~300자 key를 받는다.
동일 cancel key/payload는 최초 응답을 재생하며 다른 payload는 충돌로 거절한다. 잔여 금액을 넘는
취소를 막고, 결제별 환불 reference를 구분하며, confirmation replay가 cancel 이력을 지우지 않는다.
이 PR은 명시적 perf driver만 바꾸며 실제 Toss gateway와 업무 환불 상태 기계를 완화하지 않는다.

### Non-goals

타 PR의 코드, 업무 정책 숫자 변경, schema migration, 전체 용량 보장.

## Business Rules and Invariants

기존 거래 원자성, 중복 보호, 실패 노출 및 ADR의 불변식을 유지한다.

## Architecture and Transaction Boundaries

`refund:rejection:<eventId>`를 포함하는 공백 없는 printable ASCII 1~300자 key를 받는다.
동일 cancel key/payload는 최초 응답을 재생하며 다른 payload는 충돌로 거절한다. 잔여 금액을 넘는
취소를 막고, 결제별 환불 reference를 구분하며, confirmation replay가 cancel 이력을 지우지 않는다.
이 PR은 명시적 perf driver만 바꾸며 실제 Toss gateway와 업무 환불 상태 기계를 완화하지 않는다.


## Alternatives Considered

기존 동작 유지와 공통 기반 전체 병합을 검토했다. 측정된 비용/계약 문제만 독립 변경해 검토와 rollback 범위를 줄인다.

## Failure Semantics

드라이버의 기존 10,000건 자동 제거 경계는 다음 보관 한도 PR에서 분리 수정한다. 장시간 측정에는 두 PR을 함께 배포해야 한다. 금융 이벤트 소비 완료는 이 PR 범위 밖의 잔여 문제다.

## Data and Migration

Flyway 변경 없음. 측정 DB를 삭제하거나 실패 상태를 성공으로 보정하지 않는다.

## API and Event Contracts

공개 API/event shape 변경 없음. 드라이버의 기존 10,000건 자동 제거 경계는 다음 보관 한도 PR에서 분리 수정한다. 장시간 측정에는 두 PR을 함께 배포해야 한다. 금융 이벤트 소비 완료는 이 PR 범위 밖의 잔여 문제다.

## Milestones

개별 회귀 확인 → 관련 ADR 및 구현 → 독립 브랜치 검증 → 실제 통합 부하와 캡처 → PR 증거 기록.

## Required Tests

수정 전 성공 승인 부하 3,678건은 자동 거절 후 `TOSS_CANCEL_INVALID_REQUEST`와
`TOSS_REFUND_LOOKUP_AMBIGUOUS`를 거쳐 환불 UNKNOWN으로 남았다. 재현 테스트에서 기존 드라이버의
환불 관련 4개 assertion이 실패했다. 분리한 refund branch는 **9/9 Node 테스트 통과**했다.
수정 후 예열141+5/s301 = **442건은 환불·보상·정원 복구 SUCCEEDED**를 DB로 확인했다.
이는 콜론 계약 오류 제거와 환불 흐름의 증거다. 과거 UNKNOWN 8270건은 MANUAL_REVIEW로 남겨
성공으로 바꾸지 않았다. 실제 Toss 결제/환불을 실행한 결과가 아니다.

[공식 Toss 멱등키 문서](https://docs.tosspayments.com/reference/using-api/authorization)의
최대 300자 및 최초 응답 재생 계약을 참조했다. printable 문자 제한과 시나리오 선택은 perf harness 계약이다.


## Validation Commands

```bash
node --test infra/perf/toss-driver.test.mjs
bash scripts/verify-docs.sh
```

## Observability

[측정 보고서](../../quality/performance-perf-refund-contract-2026-09-08.md)에 native 결과와 Grafana 캡처 및 비교 한계를 기록한다.

## Documentation Updates

[관련 ADR](../../adr/ADR-121-performance-observability-and-trace-profile-correlation.md)과 측정 보고서를 포함한다.

## Progress

- [x] 코드/테스트/ADR을 변경별로 추출
- [x] 해당 경로 자동 검증 및 통합 배포/부하 확인
- [x] 측정 제한과 후속 문제 기록

## Surprises & Discoveries

SQL 또는 환불 계약 개선과 HTTP 처리량 개선은 다른 주장이다. 20/s 측정에서 전체 처리량 개선이 입증되지 않았다.

## Decision Log

2026-09-08: 개별 변경은 독립 PR로 만들고 integrated load를 단일 변경의 인과 효과로 사용하지 않는다.

## Outcomes & Retrospective

로컬 회귀와 배포 evidence를 기록했다. COMPLETED는 추출/검증 문서 완료이며 원격 PR 병합이나 전체 용량 검증 완료를 뜻하지 않는다.

## Revision Notes

2026-09-08: 수정별 PR 제출용으로 통합 구현의 범위·검증·미해결 사항을 정리했다.
