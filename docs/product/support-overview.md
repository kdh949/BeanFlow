# Customer Support Overview

> **Policy status:** Mixed — confirmed product decisions are Accepted only where recorded in Business Policy/Accepted ADR;
> exact roles, vocabulary and implementation surfaces are DRAFT planning inputs.
> **Production legal status:** Legal review required before production.

BeanFlow Customer Support는 고객·점주·매장 직원·외부 라이더의 문의를 `SupportCase`로 추적하고, 각 소유 Context의 공개 Application API를 통해 조회·변경을 조정한다. Support는 Ordering, Payment, Loyalty, Promotion, Settlement, Identity, Merchant, Delivery의 테이블이나 Repository를 직접 수정하지 않는다.

## Actors and responsibilities

The role names and bundle descriptions in this table are DRAFT. Actor separation and the R3 approval order are Accepted;
the exact organization/permission mapping is finalized by its owning implementation Stage.

| Actor | Responsibility |
|---|---|
| Support Agent | Case 접수·담당, 마스킹 조회, 본인확인, 허용된 작업 요청·실행 |
| Support Supervisor | 일상 escalation과 중간 보상 승인 |
| Support Specialist | R2/R3 및 고위험 요청 작성 |
| Support Manager | R3 첫 승인; 요청자·Operations 승인자와 달라야 함 |
| Operations Reviewer/Manager | 조사, R3 두 번째 승인, reconciliation, LegalHold |
| Privacy Auditor | PII·break-glass·retention 접근 검토; 일반 작업 실행 불가 |
| Customer/Store Member/Rider | Case의 Requester 또는 Subject; 관계와 등록 채널이 권한 입력 |

## Included capabilities

- Case, interaction, note, subject link, assignment/state history
- exact protected search와 기본 마스킹, 제한형 PII reveal
- staged verification, risk-based action decision, dual approval
- lifecycle-aware cancellation/reschedule와 post-acceptance resolution
- versioned goodwill compensation, purpose-specific profile change
- canonical DeliveryFulfillment와 Provider reconciliation
- retention classes, expiring LegalHold, deletion ledger
- 최종 제품 범위의 Support Console surface; frontend/trust/deployment boundary는 ADR-090의 Proposed decision

## Product boundaries

전화 교환기, 실시간 채팅, 자동 상담 배분, workforce/SLA platform, 범용 첨부, 범용 rules engine, 자체 rider platform, 장기 위치 궤적, 선제 Elasticsearch는 제외한다. 고위험 사건은 암호화·접근통제·보존정책이 있는 제한형 `EvidenceReference`만 허용한다.

## Source of truth

- 제품 수치와 정책: [Business Policy Decisions](business-policy-decisions.md)
- Case와 작업 정책: 이 디렉터리의 `support-*.md`
- 구조와 상태: `docs/architecture/support-*.md`
- Draft API inventory: `docs/api/support-api-surface.md`; typed Stage contract만 이후 target OpenAPI에 추가
- 구현 순서: `docs/exec-plans/active/customer-support-program-orchestration.md`와 상세 S10 plan
