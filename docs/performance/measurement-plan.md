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

- nearby store search at multiple data sizes
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
