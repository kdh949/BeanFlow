# 공유 자원 변동에 안정적인 주문 견적 구현과 배포 검증

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** —
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

다른 고객의 주문이 공유 재고나 픽업 슬롯 사용량을 바꾸더라도 구매 조건과 잔여량이 유효하면
기존 견적으로 주문할 수 있게 한다. 정책·코드·회귀 테스트를 갱신하고 검증한 이미지를 perf 서버에
배포한 뒤 동일 부하 조건으로 오류·지연·자원 사용을 다시 측정한다.

## Current State

v2 fingerprint가 재고 quantity/version과 슬롯 count/version을 포함한다. 배포 중인 perf API의
Toss-success 5 workflow/s, 90초 실행에서 450건 중 48건이 stale였다. 2회 견적 후 순차 주문하는
별도 재현도 실패했다. Inventory/Fulfillment는 이미 최종 transaction의 row lock 아래에서 현재
가용성을 검사하므로 fingerprint material만 좁혀도 초과 예약 보호를 유지할 수 있다.

## Definitions

- 거래 조건: 고객 입력·가격·구성·혜택 귀속·픽업 시간/정원 등 재확인이 필요한 의미.
- 사용량: 다른 주문의 예약·확정·해제에 따라 변하는 현재 재고/슬롯 카운터.
- workflow/s: quote→order→payment 사용자 흐름의 초당 시작 수이며 HTTP RPS와 다르다.

## Scope

### In Scope

BR-49와 ADR 갱신, v3 canonical fingerprint, PostgreSQL 회귀/동시성 테스트, OpenAPI 설명 정합성,
기존 관측성 변경의 검증·커밋, feature 브랜치 push와 이미지 workflow, 단일 API 배포와 재측정.

### Non-goals

DB schema, transaction/lock 순서, Provider/결제 상태, UI 자동 재시도, 견적 hold, main merge 변경.

## Business Rules and Invariants

BR-49의 비예약 quote, BR-25의 terminal replay와 BR-05의 실제 예약 가용성 검증을 유지한다.
가격·옵션·쿠폰·포인트 provenance·픽업 시간/정원 변경은 재확인 대상이다. 사용량 변화만으로
stale되지 않지만 부족한 재고/슬롯의 주문은 명시적으로 실패한다. 자동 수락/fallback은 없다.

## Architecture and Transaction Boundaries

Ordering의 `OrderQuoteCoordinator`가 quote/final-create에 동일 v3 함수를 사용한다.
`OrderCreationTransaction`의 Store root→owner row lock→검사→예약/Order commit 순서는 유지한다.
Inventory StockItem과 Fulfillment PickupSlot의 검사·상태 전이 코드는 변경하지 않는다.

## Alternatives Considered

전체 usage version 비교, 금액만 비교, client 자동 재견적과 quote hold는
[ADR-123](../../adr/ADR-123-order-quote-trade-terms-and-shared-availability.md)의 이유로 제외한다.

## Failure Semantics

가용성 부족은 기존 `STOCK_NOT_AVAILABLE`/`PICKUP_SLOT_FULL`, 조건 변경은 `ORDER_QUOTE_STALE`다.
실패 시 거래 write는 rollback하고 BR-25 응답을 저장·재생한다. 의존성 오류를 성공으로 바꾸지 않는다.
배포 실패 시 기존 이미지와 Compose override를 복원하고 health를 재확인한다.

## Data and Migration

migration은 없다. 앱은 Doppler, 모니터링은 기존 env 파일을 사용한다. 승인된 기존 DB/volume을 유지한다.
합성 테스트 데이터만 사용하고 원본 credential/cookie/fixture는 저장소 밖의 권한 제한 파일에 둔다.
누적 주문/예약과 background worker 때문에 엄격한 동일 DB 상태 비교가 아니라는 한계를 기록한다.

## API and Event Contracts

request/response/event shape는 그대로다. OpenAPI의 낡은 v1 설명을 v3 거래 조건과 별도 가용성 검사로
갱신한다. 배포 전 v2의 미제출 견적은 재조회·재확인과 새 key가 필요하며 terminal replay는 유지한다.

## Milestones

1. 정책·ADR·계획을 먼저 갱신한다.
2. old code에서 새 회귀 테스트 실패를 확인하고 최소 fingerprint 변경 후 관련 검증을 통과시킨다.
3. diff를 검토하고 관측성/견적 변경을 논리적 커밋으로 나누어 현재 feature 브랜치에 push한다.
4. `build-personal-staging-images.yml`을 해당 ref로 dispatch하고 exact SHA 이미지의 성공을 확인한다.
5. 앱의 이미지/설정 backup과 rollback 절차를 준비해 API만 배포하고 DB identity·health를 검증한다.
6. 1/s와 5/s를 재측정한 뒤 오류/포화가 없으면 제한된 추가 부하를 측정하고 Grafana를 직접 확인한다.

## Required Tests

- 공유 재고만 변하는 경우와 공유 슬롯만 변하는 경우 사전 견적 성공
- 예약/확정/해제 사용량 전이와 충분한 자원 견적 안정성
- 사전 견적 두 고객 동시 주문: 충분한 자원 성공, 마지막 재고/슬롯 초과 예약 방지
- 기존 가격/옵션/혜택/정원 stale, 무부수효과, terminal replay, writer 직렬화
- Ordering 통합·API 계약, 관련 Inventory/Fulfillment, 구조 검증과 formatter
- 문서/OpenAPI, 관측성/배포/load tooling 계약, packaged image smoke

## Validation Commands

```bash
./gradlew test --tests '*OrderQuoteIntegrationTest' --tests '*CreateOrderConcurrencyTest'
./gradlew spotlessCheck test --tests '*ordering*' --tests '*inventory*' --tests '*fulfillment*' --tests '*architecture*' bootJar
bash scripts/verify-docs.sh
bash scripts/perf/test-observability-contract.sh
gh workflow run build-personal-staging-images.yml --ref feature/load-test-monitoring-foundation
```

실행별 결과와 actual commit/run/image SHA를 Progress/Outcomes와 실측 보고서에 기록한다.

## Observability

native k6 summary의 workflow 완료·실패·stale·dropped iteration을 최종 판정으로 사용한다.
같은 test_id/time window의 Grafana에서 HTTP p95/p99, Hikari active/pending, PostgreSQL lock/wait,
CPU/memory와 trace를 확인한다. live rate를 최종 건수로 오인하거나 No data를 0으로 해석하지 않는다.

## Documentation Updates

BR-49, ADR-123/ADR-116 status/ADR index, OpenAPI 설명, 기존 관측성 계획의 후속 범위 링크,
운영 runbook의 배포 호환성과 `docs/quality/performance-load-rca-2026-09-07.md` 재측정 결과.

## Progress

- [x] 2026-09-07 사용자 정책 수정 및 commit/push/build/deploy/재측정 승인
- [x] 기존 owner 잠금·가용성 검사와 v2의 과도한 사용량 비교 확인
- [x] BR-49, ADR-123과 계획 갱신
- [x] v2에서 19개 중 새 회귀 3개 실패 확인: 재고만 갱신, 슬롯만 갱신, 충분한 자원 동시 주문
- [x] v3의 동일 회귀 19개 통과, formatter와 문서/OpenAPI 검증 통과
- [ ] Ordering/Inventory/Fulfillment/architecture 및 배포 키 관련 전체 검증 완료
- [ ] 커밋·push와 이미지 workflow 성공
- [ ] API 배포·health/DB 확인
- [ ] 재측정·Grafana 확인·분석 기록

## Surprises & Discoveries

첫 수정 후 검증은 기존 Kotlin 증분 classpath cache 파일 누락과 cache 등록 충돌로 compileTestKotlin에서
실패했다. Java 21 및 `-Pkotlin.incremental=false`의 전체 재컴파일 후 동일 19개 테스트가 통과했다.
소스나 도메인 테스트 실패를 무시한 것이 아니라 로컬 compiler cache 오류를 분리했다.

새 v2 기준선 `bf-0907-v2-before-r5`는 5/s·90초·VU 10/20에서 446건 중 39건 stale, 미투입 4건이었다.
발생기에서 로컬 Gradle/contract 검증도 병행했으므로 지연/미투입의 발생기 간섭 가능성을 배제하지 못한다.
배포 후 실행은 발생기 검증을 종료하고 진행하며 이 실행을 엄격한 지연 개선율 근거로 사용하지 않는다.

OpenAPI는 v1, 구현은 v2라고 설명해 version 설명이 이미 어긋나 있었다. v3 배포와 함께 바로잡는다.
앱의 sudo는 passwordless가 아니므로 root 소유 설정 반영은 검증된 배포 bundle의 1회 sudo 실행이
필요할 수 있다. Doppler 배포 token은 읽기 전용이며 이 변경은 secret 쓰기를 요구하지 않는다.

## Decision Log

- 2026-09-07: 사용자 승인에 따라 공유 사용량만 fingerprint에서 제외하고 잠금 아래 가용성 검사를 유지.
  실제 정책인 슬롯 정원과 고객별 benefit provenance는 비교 유지. ADR-123이 ADR-116을 대체한다.

## Outcomes & Retrospective

구현·CI·배포·재측정은 Pending이다. 이전 실측은 원인과 기준 자료이며 새 코드의 개선 증거가 아니다.

## Revision Notes

- 2026-09-07: 관측성 실측으로 드러난 정책 문제의 후속 구현·배포·재측정 계획 추가.
