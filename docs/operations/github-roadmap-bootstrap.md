# GitHub Roadmap Bootstrap

BeanFlow의 에픽·작업 이슈, 라벨, 마일스톤과 네이티브 Sub-issue 관계를 저장소의 현재 설계 문서와 소스 구조를 기준으로 생성한다.

## 안전 원칙

- 기본 실행 모드는 `plan`이며 GitHub를 변경하지 않는다.
- `apply`는 이슈 본문의 숨은 키(`beanflow-roadmap-key`)로 기존 항목을 찾아 갱신한다.
- 재실행해도 같은 에픽·작업을 중복 생성하지 않는다.
- 스크립트는 기존 이슈를 자동 삭제하거나 닫지 않는다.
- 기존 이슈의 완료 여부와 댓글을 보존하고 제목·본문·라벨·마일스톤만 정의와 동기화한다.
- 네이티브 Sub-issue 연결이 이미 존재하면 다시 추가하지 않는다.

## GitHub Actions 실행

1. 이 변경을 기본 브랜치에 병합한다.
2. **Actions → Bootstrap GitHub roadmap → Run workflow**로 이동한다.
3. 먼저 `mode=plan`을 실행해 31개 에픽·93개 작업 정의를 검증한다.
4. 결과를 확인한 뒤 `mode=apply`를 실행한다.

Workflow의 `GITHUB_TOKEN`에는 최소 `contents: read`, `issues: write`만 부여한다. 이 권한으로 이슈, 라벨, 마일스톤과 Sub-issue를 관리한다.

## 로컬 실행

Node.js 20 이상과 Issues write 권한이 있는 GitHub token이 필요하다.

```bash
node --test scripts/github-roadmap/roadmap.test.mjs

# 변경 없이 정의 검증
node scripts/bootstrap-github-roadmap.mjs \
  --mode plan \
  --repo kdh949/BeanFlow

# 실제 생성/갱신
GITHUB_TOKEN='replace-me' node scripts/bootstrap-github-roadmap.mjs \
  --mode apply \
  --repo kdh949/BeanFlow
```

Token은 저장소 파일이나 셸 히스토리에 커밋하지 않는다.

## 생성 구조

```text
[Roadmap] BeanFlow 개발 로드맵
└─ [Epic E01] ...
   ├─ [E01/T1] ...
   ├─ [E01/T2] ...
   └─ [E01/T3] ...
```

각 작업 이슈에는 다음을 포함한다.

- 현재 구현 기준과 관련 소스 파일
- 보호해야 할 도메인 불변식
- 구체적인 구현 단계
- 예상 변경 파일
- 필수 단위·통합·동시성·계약 테스트
- 관련 Business Rule·ADR·아키텍처 문서
- 공통 검증 명령

## 수정 방법

로드맵 내용은 `scripts/github-roadmap/definitions/`에서 마일스톤별로 관리한다. 정의를 바꾼 뒤 반드시 다음을 실행한다.

```bash
node --test scripts/github-roadmap/roadmap.test.mjs
node scripts/bootstrap-github-roadmap.mjs --mode plan --repo kdh949/BeanFlow
```

제품 동작이나 구조적 결정이 바뀌면 로드맵 정의만 수정하지 않고 관련 Business Policy 또는 ADR을 먼저 갱신한다.
