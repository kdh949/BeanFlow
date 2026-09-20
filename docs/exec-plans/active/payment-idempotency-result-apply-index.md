# 결제 승인 result_apply의 멱등성 조회를 인덱스로 제한

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/active/performance-capacity-and-journey-load-validation.md`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다. 사용자 변경이 있는 원 checkout을 보존하고 latest
`origin/main`에서 만든 격리 worktree `feature/payment-idempotency-index`만 사용한다.

## Purpose / Big Picture

결제 승인 `result_apply`에서 반복되는 `payment_idempotency_record.payment_id` equality 조회의 전체 scan을
제거한다. 제품 동작이나 transaction 수는 바꾸지 않고 index 한 개만 변경한 동일 조건 A/B로 SQL 시간과
CPU 기여를 확인한다.

## Progress

- [x] 2026-09-20 저장소 정책, failure semantics, DoD, ADR-072/121과 성능 ExecPlan 확인.
- [x] 2026-09-20 dirty worktree 보존 및 latest `origin/main` `e47cd05` 격리 branch 생성.
- [x] 2026-09-20 원격 migration inventory V91, 열린 PR #189 V87/#190 V88 충돌 상태 확인.
- [x] 2026-09-20 V92 migration, PostgreSQL 17.5 query-plan 회귀 테스트와 ADR 검증.
- [x] 2026-09-20 Payment focused regression과 문서 검증 통과. 전체 check는 local-demo process guard의
  기존 환경 의존 2건 실패를 격리 재현해 PR CI 확인 대상으로 기록.
- [ ] Draft PR 생성과 CI 확인.
- [ ] 스테이징 적용 전 동일 조건 기준선 및 불변식/recovery 확인.
- [ ] V92와 동일한 DDL만 스테이징에 적용하고 index 정의/크기/lock 결과 확인.
- [ ] 동일 조건 개선군 측정, Grafana 캡처와 전후 표 기록.
- [ ] 결과 문서화, 완료 이동과 final diff 검토.

## Scope and Guardrails

- Payment transaction, Session, Audit, Event Publication, Order/Idempotency 상태 전이와 공개 API는 변경하지 않는다.
- 원문 SQL, 사용자·주문·결제 식별자를 metric label이나 보고서에 기록하지 않는다.
- A/B는 같은 기존 스테이징 DB, API image, driver, fixture와 12 workflow/s·VU 80·3분을 사용한다.
- container exporter success와 API/PostgreSQL CPU 시계열이 없으면 부하를 실행하지 않고
  `OBSERVABILITY_GAP`으로 기록한다.
- dropped 0, failure 1% 미만, DB 불변식 0, recovery 10분 이내를 비교 gate로 사용한다.
- 스테이징 DB는 V90+별도 V87이고 latest main은 V91이므로 새 application image로 Flyway 전체를 적용하지
  않는다. V92 파일과 byte-equivalent DDL만 부하가 없는 구간에 적용해 단일 변수를 보존한다.

## Migration Lane

`origin/main`의 마지막 migration은 V91이다. 열린 PR #189/#190은 V87/V88을 포함하지만 둘 다 main과
충돌하고 main inventory보다 낮다. 이 plan은 그 branch를 변경·merge·재번호화하지 않고 V92만 소유한다.
repository-wide release 순서의 불일치는 숨기지 않으며 PR은 해당 gate가 해소될 때까지 Draft로 둔다.

## Validation Plan

1. 25,000-row fixture에서 index 전 Seq Scan과 V92 named index plan을 캡처한다.
2. Payment 승인·UNKNOWN/reconciliation regression과 full check/docs를 실행한다.
3. exporter gate 뒤 기준선 warm-up/본 실행, recovery와 invariant를 기록한다.
4. index 적용 후 같은 warm-up/본 실행과 recovery/invariant를 반복한다.
5. SQL seconds/workflow, API/Postgres/host CPU seconds/workflow, latency, Hikari와 dropped의 절대값·변화율을
   계산한다. CPU 또는 처리량 경계가 움직이지 않으면 전체 CPU 병목 해결로 주장하지 않는다.

## Decision Log

| Date | Decision | Reason |
| --- | --- | --- |
| 2026-09-20 | 단일-column `payment_id` index | 관측 predicate와 최소로 일치하고 제품 의미를 바꾸지 않음 |
| 2026-09-20 | Draft PR과 V92 release gate | 오래된 열린 migration PR의 불일치를 숨기거나 임의 해결하지 않음 |
| 2026-09-20 | 스테이징에 동일 DDL만 선적용 | V91 등 다른 변수를 섞지 않고 기존 데이터 A/B를 유지 |

## Outcomes & Retrospective

고정 25,000행 fixture에서 V92 전 `Seq Scan`은 24,999행을 제거하고 shared hit 658,
execution 3.465ms였다. 동일 SQL의 V92 후 결과는 named `Index Scan`, shared hit 1/read 2,
execution 1.081ms였다. 단일 warm capture의 68.8% query execution 감소는 access-path 증거이며 실제 CPU
개선 주장이 아니다. `PaymentConfirmationIntegrationTest`와 migration test는 통과했고 문서 검증은
18 tests, 135 ADRs, 384 Markdown, 111 ExecPlans를 통과했다. 전체 `check`는 변경 파일과 무관한
`LocalDemoScriptGuardTest`의 process 실행/소유권 2건이 같은 로컬 환경에서 격리 재현돼 실패했다.
실측과 PR CI 완료 후 갱신한다.
