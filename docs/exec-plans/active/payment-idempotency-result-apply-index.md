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
- [x] 2026-09-20 Draft PR #198 생성. 2026-09-21 CodeQL, backend build와 6개 test shard 포함 CI 통과.
- [x] 2026-09-21 스테이징 적용 전 동일 조건 12/s 기준선 및 불변식/recovery 통과.
- [x] 2026-09-21 V92와 동일한 DDL만 스테이징에 적용하고 index 정의/크기/health 확인.
- [x] 2026-09-21 동일 조건 개선군 측정, recovery/invariant와 Grafana 전후 캡처 완료.
- [x] 2026-09-21 결과 문서화와 final diff 검토. PR은 migration lane gate 때문에 Draft 유지.
- [x] 2026-09-21 PR #189가 병합된 최신 main을 통합하고 #190의 별도 V92 충돌을 확인해 #198을 실제 Draft로
  되돌림.
- [x] 2026-09-21 리뷰 후 V92에 5초 lock/60초 statement timeout과 exact index definition 검증을 추가하고,
  올바른 선적용 index 허용·잘못된 동일 이름 index 거부 회귀 테스트를 추가함.
- [x] 2026-09-21 최신 main 기준 V92 migration·Payment confirmation·Flyway smoke·spotless, 테스트 제외
  전체 build와 문서 검증(18 tests, 136 ADRs, 385 Markdown, 111 ExecPlans) 통과.

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

`origin/main`의 마지막 migration은 V91이고 PR #189의 V87은 이미 병합됐다. PR #190은 최신 main을
통합하면서 demo migration을 별도 V92로 재번호해 #198과 version collision 상태다. #198은 먼저 시작돼
스테이징 A/B와 정식 migration이 V92에 고정됐으므로 Draft에서 V92 lane을 유지한다. 이 PR의 리뷰·CI와
병합이 끝난 뒤 #190이 최신 main을 통합하고 아직 미적용인 demo migration을 V93으로 옮긴다. 두 V92가
동시에 main에 들어가거나 V93이 V92보다 먼저 release되는 순서는 허용하지 않는다.

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
| 2026-09-21 | 12/s A/B를 query/component 원인 확정에 사용 | 단일 변수로 SQL·CPU·Hikari가 함께 이동했지만 capacity 경계는 별도 검증 대상 |
| 2026-09-21 | V92에 bounded transactional build와 exact definition 검증 | lock 대기와 이름-only 선적용 수용을 fail-closed 처리 |
| 2026-09-21 | #198 V92 선행, #190 V93 후행 | #198의 기존 스테이징 증거를 보존하고 Flyway out-of-order release 방지 |

## Outcomes & Retrospective

고정 25,000행 fixture에서 V92 전 `Seq Scan`은 24,999행을 제거하고 shared hit 658,
execution 3.465ms였다. 동일 SQL의 V92 후 결과는 named `Index Scan`, shared hit 1/read 2,
execution 1.081ms였다. 단일 warm capture의 68.8% query execution 감소는 access-path 증거이며 실제 CPU
개선 주장이 아니다. `PaymentConfirmationIntegrationTest`와 migration test는 통과했고 문서 검증은
18 tests, 135 ADRs, 384 Markdown, 111 ExecPlans를 통과했다. 전체 `check`는 변경 파일과 무관한
`LocalDemoScriptGuardTest`의 process 실행/소유권 2건이 같은 로컬 환경에서 격리 재현돼 실패했다.
2026-09-21 기존 스테이징 DB에 byte-equivalent V92 DDL만 선적용하고 같은 image, driver, fixture,
12 workflow/s, VU 80, 3분 조건으로 비교했다. 대상 lookup 실행시간은 25.849초에서 0.0492초로 99.81%,
idempotency SQL seconds/workflow는 73.82%, PostgreSQL/API/host CPU/workflow는 각각 14.09%/10.61%/13.01%,
Hikari acquire p99는 43.09%, workflow p95는 13.53% 감소했다. 양쪽 모두 2,160개 이상 workflow,
dropped/failure 0, 14개 invariant 위반 0, active recovery 0을 충족했다. HTTP p95는 6.65% 악화됐지만
230.81ms로 guardrail 안이고 p99는 악화되지 않아 반복 전에는 잡음 여부를 확정하지 않는다.

판정은 이 lookup의 **Confirmed improvement**다. 실제 staging plan도 named Index Scan을 사용했고 SQL, CPU와
pool tail이 단일 변수에 함께 반응했으므로 미인덱스 조회가 CPU 포화의 물질적 기여 원인임을 확정한다.
다만 15/s 반복과 18/s 경계는 이 plan에서 실행하지 않았으므로 전체 CPU 포화 해결이나 capacity boundary
이동은 주장하지 않는다. 기존 PR #198 CI는 통과했다. 리뷰 보완 뒤에는 최신 main 기준 CI를 다시 실행한다.
PR은 #190의 V92가 V93으로 이동할 때까지 Draft로 두며, 스테이징의 수동 선적용은 Flyway history에 V92를
삽입하지 않았다. 정식 V92는 선적용 index의 definition/valid 상태를 검증한 뒤에만 migration history에
성공을 기록한다. 리뷰 보완의 focused regression, Flyway smoke, Payment confirmation, spotless와
테스트 제외 build, 문서 검증은 최신 main 통합 상태에서 통과했다. 전체 원격 test shard와 CodeQL은 push
후 다시 확인한다.
