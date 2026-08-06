# Performance Measurement Plan

## Process

```text
증상 관찰
-> 재현 조건 고정
-> 기준선 측정
-> 로그·metric·실행계획 수집
-> 병목 가설
-> 최소 변경
-> 동일 조건 재측정
-> 회귀 테스트
-> 결과·비용 기록
```

## Required context

- 실행 환경과 resource limit
- 애플리케이션·JVM·PostgreSQL version
- 데이터 규모와 분포
- VU와 요청 패턴
- warm-up 여부
- RPS
- p50, p95, p99
- 오류율과 오류 종류
- SQL 횟수와 query time
- `EXPLAIN (ANALYZE, BUFFERS)`
- Lock Wait
- Hikari active, idle, pending
- JVM heap, allocation, GC
- CPU, memory와 thread
- 외부 Provider latency
- 변경 전후 동일 조건 여부
- 추가 비용과 한계

## Initial scenarios

- nearby store search at multiple data sizes (measured 2026-08-07)
- order list query and N+1
- last stock/slot/coupon contention
- external PG latency with transaction inside versus outside only as controlled experiment
- settlement batch chunk size
- point expiration batch
- notification retry backlog

## Reporting language

- `Target`: 아직 달성하지 않은 품질 기준
- `Assumption`: 초기 정책·환경 가정
- `Measured`: 실제 조건과 함께 측정한 결과
- `Not measured`: 수치가 없음
- `Revisit when`: 재검토 조건

측정하지 않은 개선율을 작성하지 않는다.

## Nearby store search plan and latency (2026-08-07)

- **Reproduce:** `scripts/perf/nearby-store-search.sh`. dataset은 `random()`이 아니라 닫힌 식으로
  생성해 같은 행이 재현되며, 좌표·radius·limit·warm-up·반복 횟수를 코드에 고정했다.
- **Measured:** 10,000/100,000 profile 두 규모에서 `EXPLAIN (ANALYZE, BUFFERS)`와 200회 반복
  latency 분포. 두 규모 모두 GiST bounding-box index condition을 사용했고 p50은 0.397 ms →
  1.850 ms였다. 증가 요인은 전체 행 수가 아니라 반경 안 후보 수(27 → 265)였다.
- **Not measured:** native amd64 timing, 동시 부하와 RPS, 부하 시 오류율, GC/allocation,
  connection pool 거동, multi-page cursor 비용, 비균등 분포, 100,000건을 넘는 규모.
- 비교 가능한 기준선이 없으므로 성능 개선을 주장하지 않는다. 컨테이너는 emulation으로 실행됐다.
- 첫 실행의 `p50 = 117.936 ms`는 호출마다 새 connection을 여는 측정 결함이었고 원인과 함께
  evidence 문서에 남겼다.
- 상세 조건과 실제 값은 [nearby query plan evidence](../quality/nearby-store-discovery-performance-evidence.md)에 있다.
- **Revisit when:** select list, sort tuple, index 정의, radius 계약, 최대 page size 또는
  PostgreSQL/PostGIS version이 바뀌거나 실제 매장 밀도 분포와 SLO가 생길 때.

## Settlement lifecycle measurement (2026-08-03)

- **Measured environment:** Apple Silicon local workstation, Java 21 test worker, PostgreSQL 17.6
  Testcontainers, full V1~V30 schema, 단일 application test process. 별도 CPU/memory limit과 warm-up은
  두지 않았다.
- **Fixture:** 한 store/date Batch, immutable SettlementItem 1,000건, 각 800 KRW net,
  application chunk 500, Adjustment 0건. DB `ANALYZE settlement_item` 뒤 측정했다.
- **Query plan:** 첫 500건 keyset query는 `idx_settlement_item_batch_cursor` index scan,
  shared hit 20, planning 0.110ms, execution 0.119ms였다. plan은
  `EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)`로 수집했다.
- **Application duration:** calculation 36.054ms, confirmation 17.260ms의 단일 관측이다.
- **Lock wait:** 첫 connection이 같은 Batch row의 `FOR UPDATE` lock을 의도적으로 200ms 보유한
  뒤 두 번째 connection의 관측 대기는 203.086ms였다. 이는 contention 목표가 아니라 lock
  직렬화와 측정 경로가 실제 동작하는지 확인한 제어 실험이다.
- **Not measured:** warm p50/p95/p99, RPS, GC/allocation, Hikari pending, 대규모 multi-store
  backlog와 운영 I/O. 따라서 이 기록으로 SLA, 최대 처리량 또는 성능 개선을 주장하지 않는다.
- **Revisit when:** 실제 일별 Item 분포, Batch backlog 또는 SLO가 생기면 동일 schema/query/chunk를
  고정하고 10k/100k Item, multi-store parallelism과 pool/GC를 포함해 재측정한다.
