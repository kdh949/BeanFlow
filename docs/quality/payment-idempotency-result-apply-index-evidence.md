# 결제 멱등성 조회 인덱스 성능 측정

## PostgreSQL 실행계획

- 측정일: 2026-09-20
- 재실행: `./gradlew test --tests '*PaymentIdempotencyQueryMigrationTest*' --rerun-tasks`
- DB: Testcontainers의 PostgreSQL 17.5
- 데이터: payment ID가 서로 다른 멱등성 기록 25,000건, 조회 대상 1건
- 조회: `findByPaymentId(paymentId)`에서 사용하는 Entity 조회 SQL
- 방법: Flyway V1~V92 적용 후 동일 스키마에서 V92 인덱스 추가 전후의 `EXPLAIN (ANALYZE, BUFFERS)`를 수집했다. PostgreSQL planner 설정은 변경하지 않았다.

| 조건 | 실행계획 | 제외된 행 | 버퍼 | 실행시간 |
| --- | --- | ---: | --- | ---: |
| 인덱스 적용 전 | `Seq Scan` | 24,999 | shared hit 658 | 3.465 ms |
| 인덱스 적용 후 | `Index Scan using idx_payment_idempotency_payment_id` | 0 | shared hit 1, read 2 | 1.081 ms |

인덱스 적용 후 `Seq Scan`이 `Index Scan`으로 바뀌었고, 조회 실행시간은 **3.465ms → 1.081ms**였다.

## Staging A/B

### 실행 조건

- 측정일: 2026-09-21 KST
- 환경: 4-core 서버의 기존 스테이징 DB `beanflow_perf_prr_gcbase2_r12_b1`
- API 이미지: `sha256:9b53ce64f27f3752e7c6776ef83b075e5d3e3d1c1a4058d461d12c43b6b87178`
  (`org.opencontainers.image.revision=3c566948429c73c9bf7c89f18c1cc85a9a82eac8`) (두 구간 동일).
- Toss 테스트 드라이버: `beanflow-perf-toss-driver-gcaudit-r12-b1`; canonical dataset digest
  `f46a92b22dcfaedd0a0d69ba2665b63f05c7f1db5a16b91cf9015b52cf12d599` (두 구간 동일).
- 부하: 주문 시나리오 초당 12회, VU 80, 3분. 두 구간에서 source hash와 notification result batch 1을 동일하게 사용
- 인덱스 적용 전: `payidx-b0-r12-20260921T0010Z`, 완료 2,161건
- 인덱스 적용 후: `payidx-b1-r12-20260921T0024Z`, 완료 2,160건
- 변경 항목: V92에 해당하는 `payment_id` 인덱스. 애플리케이션 이미지·설정·DB·드라이버는 유지했다. DDL 적용 전 테이블은 26,932행·22MiB였다.
- CPU 기록: `beanflow_container_collection_success == 1`; 양쪽 구간에서 API·PostgreSQL container CPU를 수집했다.

스테이징 DB에는 Flyway V90과 일부 별도 migration이 적용돼 있었다. A/B에서 애플리케이션 코드를 바꾸지 않기 위해 V92와 동일한 DDL만 lock timeout 5초·statement timeout 60초로 적용했다. 생성된 인덱스 크기는 848KiB였고, 29,814행 시점에는 1,480KiB였다.

`flyway_schema_history`에는 수동 적용 이력을 추가하지 않았다. 정식 V92에는 `IF NOT EXISTS`가 있어 이후 적용 시 기존 인덱스를 재사용한다. 두 구간 모두 API 재시작과 OOM은 0건이었다.

### 측정 결과

조회 호출 횟수는 Prometheus `increase()`의 scrape 구간 보간값이다. 변경 전후 모두 동일한 수집기와 시간 범위 계산 방식을 사용했다.

| 지표 | 적용 전 | 적용 후 | 차이 | 변화율 |
| --- | ---: | ---: | ---: | ---: |
| 대상 조회 횟수 | 2,199.24 | 2,169.83 | -29.41 | 1.34% |
| 대상 조회 SQL 누적 실행시간 | 25.849 s | 0.0492 s | -25.800 s | **99.81%** |
| 대상 조회 SQL 평균 실행시간 | 11.754 ms/call | 0.0227 ms/call | -11.731 ms | **99.81%** |
| 시나리오당 멱등성 SQL 실행시간 | 0.017259 s | 0.004518 s | -0.012741 s | **73.82%** |
| 시나리오당 멱등성 SQL 호출 수 | 36.463 | 36.187 | -0.276 | 0.76% |
| 시나리오당 PostgreSQL CPU 사용시간 | 0.094487 s | 0.081170 s | -0.013318 s | **14.09%** |
| 시나리오당 API CPU 사용시간 | 0.118090 s | 0.105556 s | -0.012534 s | **10.61%** |
| 시나리오당 서버 CPU 사용시간 | 0.269325 s | 0.234288 s | -0.035038 s | **13.01%** |
| 시나리오당 트랜잭션 경과시간 | 0.710845 s | 0.483637 s | -0.227208 s | **31.96%** |
| Hikari 연결 획득 p99 | 57.91 ms | 32.96 ms | -24.95 ms | **43.09%** |
| 최대 대기 연결 수(수집값) | 3.6 | 0 | -3.6 | **100%** |
| 주문 시나리오 p95 | 3.424 s | 2.961 s | -0.463 s | **13.53%** |
| 주문 시나리오 p99 | 4.716 s | 4.707 s | -0.009 s | 0.19% |
| HTTP p95 | 216.42 ms | 230.81 ms | +14.38 ms | **-6.65%** |
| HTTP p99 | 447.95 ms | 444.73 ms | -3.22 ms | 0.72% |
| 시나리오당 WAL | 150,533 B | 157,232 B | +6,698 B | **-4.45%** |
| 시나리오당 메모리 할당량 | 9,383,709 B | 9,386,443 B | +2,734 B | **-0.03%** |
| 부하 종료 후 알림 작업 완료시간 | 71.36 s | 69.52 s | -1.84 s | 2.58% |

두 구간 모두 요청 누락·주문 시나리오 실패·HTTP 오류·14개 DB 조건 위반·미완료 복구가 0건이었다. 멱등성 조회 횟수는 비슷했고, 인덱스 적용 후 DB에서 `idx_payment_idempotency_payment_id`를 이용한 `Index Scan`을 기록했다.

초당 12건 조건에서 대상 SQL 누적 실행시간은 **99.81% 감소**했다. 주문 시나리오당 PostgreSQL·API·서버 CPU 사용시간과 Hikari 연결 획득 시간도 감소했다. 전체 HTTP p95는 **216.42ms → 230.81ms**, p99는 **447.95ms → 444.73ms**였다.

### Grafana 측정 화면

Grafana는 각 테스트 ID와 실행 시간을 지정해 조회했다. 서버 패널에는 해당 시간대 전체 요청이, k6 패널에는 선택한 테스트 ID의 요청만 표시된다.

| 파일 | 내용 | SHA-256 |
| --- | --- | --- |
| `grafana-before-causal-panels-final.jpg` | CPU/workflow, transaction/workflow, SQL calls | `b0f8d9bae7f90e175a469c19f7b462c2054e5b066d2fe9191d12e73aec3f6a80` |
| `grafana-after-causal-panels-final.jpg` | same panel positions and selected after test ID | `9acf8e838203ae6ccd450052e7a17ef87b9f77070414abdbb708be58dd7d9585` |
| `grafana-before-sql-resource-panels-final.jpg` | SQL seconds, WAL, allocation, GC and throwable paths | `ed2b8363ccdefb50e16983618185e5153347e3392c998e603fd1e291d62d02e7` |
| `grafana-after-sql-resource-panels-final.jpg` | same resource panels after the index | `d3f65424ce8579ddfae683525c48e34fa1fb3d24dabf1b4e1b7106fb5ddbd918` |

스크린샷은 `.ci-artifacts/payment-idempotency-index-2026-09-21/`에 저장했고, 저장소에는 파일명과 SHA-256만 기록했다.

## 다음 측정

- 테스트 데이터는 payment ID당 기록 1건으로 구성했다. 조회 조건이나 데이터 분포가 달라지면 다시 측정한다.
- 인덱스 유지로 늘어나는 쓰기·저장 비용은 추가로 측정한다.
- 다음 실험은 초당 15건·18건에서 처리량과 주문당 CPU 시간을 비교하고 실패·재시도 결과도 기록한다.
