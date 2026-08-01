# BeanFlow 기여 가이드

BeanFlow는 `main`을 항상 빌드와 배포가 가능한 상태로 유지하는 GitHub Flow를 사용합니다.

## 기본 원칙

* 모든 일반 작업 브랜치는 최신 `main`에서 생성합니다. 무인 schema-writing 작업은
  [ADR-072](docs/adr/ADR-072-execplan-unattended-execution-and-migration-lane.md)의 migration-writer
  lease를 얻은 뒤에만 시작합니다.
* 브랜치는 하나의 목적만 다루고, 가능하면 1~3일 안에 병합합니다.
* 미완성 required 기능을 feature flag/profile로 2xx 성공처럼 노출하지 않습니다. Plan 40처럼
  recovery가 선행되어야 하는 기능은 Draft PR로 유지하고 배포하지 않습니다.
* `main` 변경은 Pull Request를 통해 리뷰와 CI를 통과한 뒤 병합합니다.
* 긴급 상황에서 관리자 우회를 사용했다면 즉시 사유와 후속 검증을 Issue 또는 PR에 남깁니다.

## 브랜치 이름

영문 소문자와 하이픈을 사용합니다.

| 유형 | 형식 | 예시 |
| --- | --- | --- |
| 기능 | `feature/<설명>` | `feature/order-creation` |
| 버그 수정 | `fix/<설명>` | `fix/payment-timeout` |
| 리팩터링 | `refactor/<설명>` | `refactor/order-validation` |
| 테스트 | `test/<설명>` | `test/payment-idempotency` |
| 문서 | `docs/<설명>` | `docs/order-policy` |
| 도구·설정 | `chore/<설명>` | `chore/upgrade-gradle` |

```bash
git switch main
git pull --ff-only
git switch -c feature/order-creation
```

`Plan 40 → Plan 50`처럼 ADR-072가 명시한 Draft-only release stack만 예외로 parent head를
base로 할 수 있습니다. Plan 50 validation 후에는 child head를 main에 target한 combined release PR만
병합하며 parent Draft PR을 따로 merge/deploy하지 않습니다. 여러 sibling head를 통합한 branch 또는
여러 PR base를 자동으로 추측하지 않습니다.

이 Draft stack은 하나의 migration-writer lease를 공유합니다. Plan 40의 verified completion commit은
parent Draft branch에서 plan 파일 이동과 Plan 50 dependency/ready 갱신을 함께 수행하며, Plan 50은
그 completed parent head만 base로 삼습니다. child release PR이 main에 merge되기 전에는 unrelated
schema writer를 시작하지 않습니다.

## Migration writer lane

Flyway migration을 만들거나 수정하는 ExecPlan은 `Writes-Migration: true` metadata를 가져야 합니다.
자동 실행기는 한 번에 하나만 시작하며, lease holder가 latest main에서 branch를 만든 뒤 마지막 V 번호를
읽습니다. 해당 PR이 merge되기 전 다음 schema writer를 시작하거나 migration 번호 reservation, checksum
repair, sibling rebase로 충돌을 보정하지 않습니다.

## 커밋

커밋은 독립적으로 이해하고 되돌릴 수 있는 단위로 나눕니다. 메시지는 다음 형식을 사용합니다.

```text
<type>: <변경 의도>
```

허용하는 `type`은 `feat`, `fix`, `refactor`, `test`, `docs`, `chore`입니다.

```text
feat: 주문 생성 요청의 멱등성 키를 검증한다
fix: 결제 승인 재조회 시 완료 상태를 복원한다
```

포맷 변경, 리팩터링, 기능 변경은 가능한 한 별도 커밋과 별도 PR로 분리합니다. 공유 브랜치와 `main`에는 force push하지 않습니다.

## Pull Request

PR을 열기 전에 다음을 확인합니다.

```bash
./gradlew spotlessCheck
./gradlew clean build --stacktrace
bash scripts/verify-docs.sh
git diff --check
```

Kotlin 포맷 위반은 다음 명령으로 수정합니다. Spotless는 `origin/main` 이후 추가되거나
변경된 `src/**/*.kt` 파일만 검사하며 기존 소스를 일괄 재포맷하지 않습니다.

```bash
./gradlew spotlessApply
```

PR은 다음 기준을 충족해야 병합할 수 있습니다.

* 변경 목적과 검증 방법이 본문에 설명되어 있습니다.
* 필수 `build` 검사가 통과했습니다.
* 최소 1명의 승인을 받았습니다.
* 미해결 리뷰 대화가 없습니다.
* 관련 비즈니스 정책, ADR, Issue가 연결되어 있습니다.
* 호환성 변경과 운영 영향이 명시되어 있습니다.

리뷰 가능한 크기를 우선하며, 약 300줄을 넘는 변경은 논리적으로 나눌 수 있는지 먼저 검토합니다. 초안은 Draft PR로 공유하고, 리뷰 가능한 상태에서 Ready for review로 전환합니다.

## 병합과 정리

* 의미 있는 원자적 커밋 이력을 보존할 때는 `Create a merge commit`을 사용합니다.
* 하나의 변경으로 정리하는 편이 명확한 PR은 `Squash and merge`를 사용할 수 있습니다.
* `Rebase and merge`는 사용하지 않습니다.
* 일반 merge의 개별 커밋과 squash 커밋 제목은 모두 커밋 메시지 형식을 따릅니다.
* 병합된 작업 브랜치는 자동 삭제합니다.
* 병합 뒤 `main`의 CI 실패나 회귀가 발견되면 수정 PR을 우선하되, 즉시 복구가 필요하면 해당 PR을 revert합니다.

## 아키텍처 결정

코드를 변경하기 전에 `docs/product/`, `docs/architecture/`, `docs/adr/README.md`,
`docs/exec-plans/`와 관련 ADR을 확인합니다. 모듈·트랜잭션·데이터 소유권·동기화·
공개 API·인증·외부 연동·운영 인프라 등 되돌리기 어려운 결정을 바꾸는 작업은
구현 전에 `proposed` 상태의 ADR을 작성하고 PR에 연결합니다.
