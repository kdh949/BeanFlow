# 데모 진입 경로의 정적 디렉터리 충돌 제거

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** —
> **Completed-At:** `2026-10-03`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

OWN-03: /demo 직접 진입의 nginx 403을 제거하여 기존 데모 가능 여부 화면까지 도달한다.

## Current State

main ec61729의 frontend/public/demo/catalog 파일이 Vite public copy와 Dockerfile을 통해 html/demo 디렉터리를 만든다. 두 Nginx 설정의 try_files $uri $uri/는 디렉터리를 먼저 선택한다. 공개 GET은 /demo 301 → /demo/ 403, /demo/index.html 200이며 demo config는 disabled다. 배포 이미지 내부는 직접 조회하지 않았으므로 동일 설정의 로컬 재현과 운영 관측을 구분한다.

## Definitions

exact location은 경로 전체가 일치할 때만 적용되는 Nginx 라우팅 규칙이다.

## Scope

### In Scope

두 Nginx 설정에 /demo와 /demo/ exact SPA 진입, 실제 정적 디렉터리를 사용하는 runtime regression, CI 실행.

### Non-goals

데모 활성화, 계정 발급, 결제 설정, 전체 fallback rewrite, #201 오류 로깅 복제.

## Business Rules and Invariants

데모 발급 disabled 및 testPaymentEnabled=false 유지. API와 auth 차단, assets 404, catalog 이미지 제공 유지.

## Architecture and Transaction Boundaries

정적 frontend Nginx 경로 매칭만 변경. Aggregate/transaction/API와 외부 부수효과 변경 없음.

## Alternatives Considered

전체 try_files 변경은 다른 정적 디렉터리 의미를 바꿀 수 있어 제외. 두 exact location은 현재 충돌에만 적용된다.

## Failure Semantics

index.html이 없으면 404. /api 요청을 SPA 성공으로 숨기지 않으며 auth/admin 차단 유지.

## Data and Migration

없음.

## API and Event Contracts

변경 없음.

## Milestones

1. 변경 전 runtime 테스트 실패 확인
2. 두 설정 최소 수정
3. 변경 후 runtime 및 계약 검사, main 대상 독립 PR

## Required Tests

/demo, /demo/ HTML 200/no-store; catalog image 동일 byte; 누락 assets 404; auth/admin 404; API upstream 고유 상태 유지. 두 배포 설정 모두 실행.

## Validation Commands

bash scripts/deploy/test-demo-entry-nginx.sh; bash scripts/deploy/test-frontend-image-contract.sh; bash scripts/deploy/test-deployment-contract.sh; scripts/verify-docs.sh; git diff --check

## Observability

기존 access/error log 유지. 쿼리·개인정보 로깅 추가 없음.

## Documentation Updates

이 문서에 재현·검증과 운영 한계 기록. 정책/ADR 변경 불필요.

## Progress

- [x] 원인 경로와 운영 HTTP 증상 대조
- [x] 수정 전 실제 Nginx 301 → 403 재현
- [x] 두 Nginx 설정 runtime, 이미지 계약, 배포 계약, docs 검증 통과
- [x] main 기준 독립 PR 제출 준비

## Surprises & Discoveries

public/demo/catalog은 추적 파일이다. rg --files 기본 검색 결과만으로 디렉터리 부재를 판단할 수 없다.

## Decision Log

2026-10-03: /demo와 /demo/만 exact match하여 index.html로 내부 전환한다. 정적 catalog와 다른 서비스 경로는 유지한다.

## Outcomes & Retrospective

Passed: pinned Nginx 1.30.4 runtime에서 두 설정 모두 /demo·/demo/ 200/no-store, 기존 catalog byte 동일, 누락 assets 404, auth/admin 404, API upstream 418 유지. 기존 image/deployment contracts와 verify-docs, diff --check 통과. 테스트 컨테이너와 내부 네트워크는 종료 시 제거한다. UI source 변경 없음. 운영 배포 후 검증 Not run; 데모 disabled 설정은 변경하지 않았다.

## Revision Notes

2026-10-03: 실행 계획 작성.

