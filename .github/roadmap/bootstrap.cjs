const fs = require('fs');
const path = require('path');

const ROADMAP_KEY = 'ROADMAP';
const MARKER = (key) => `<!-- beanflow-roadmap-key: ${key} -->`;

const labels = [
  ['type:roadmap', '5319e7', '개발 로드맵 인덱스'],
  ['type:epic', '8250df', '여러 작업을 묶는 에픽'],
  ['type:task', '1d76db', '구현 가능한 작업 단위'],
  ['type:spike', 'd4c5f9', '비교·측정·설계 실험'],
  ['type:docs', '0075ca', '문서·계약·의사결정 기록'],
  ['priority:P0', 'b60205', 'MVP 필수'],
  ['priority:P1', 'fbca04', 'MVP 이후 대표 확장'],
  ['priority:P2', 'cfd3d7', '장기 확장'],
  ['area:identity', '0e8a16', 'Identity 및 접근 제어'],
  ['area:merchant', '0e8a16', '매장·메뉴·영업 정책'],
  ['area:discovery', '0e8a16', '위치 기반 검색'],
  ['area:ordering', '0e8a16', '주문 생명주기'],
  ['area:fulfillment', '0e8a16', '픽업·수행 상태'],
  ['area:inventory', '0e8a16', '판매 재고'],
  ['area:promotion', '0e8a16', '캠페인·쿠폰'],
  ['area:loyalty', '0e8a16', '포인트·로열티'],
  ['area:payment', '0e8a16', '결제·환불·대사'],
  ['area:events', '0e8a16', '영속 이벤트 전달'],
  ['area:notification', '0e8a16', '알림 발송·재처리'],
  ['area:settlement', '0e8a16', '정산·조정'],
  ['area:dispute', '0e8a16', '정산 이의제기'],
  ['area:analytics', '0e8a16', '매출 Read Model'],
  ['area:operations', '0e8a16', '운영·감사·재처리'],
  ['area:api', '0e8a16', 'REST·OpenAPI·REST Docs'],
  ['area:security', '0e8a16', '인증·인가·데이터 격리'],
  ['area:persistence', '0e8a16', 'JPA·PostgreSQL·Flyway'],
  ['area:performance', '0e8a16', '성능·부하·실행계획'],
  ['area:platform', '0e8a16', '빌드·배포·관측 기반'],
  ['area:wallet', '0e8a16', '선불 지갑'],
  ['area:pos', '0e8a16', 'POS·프린터 연동'],
  ['area:ai', '0e8a16', '점주 AI 인사이트'],
  ['area:delivery', '0e8a16', '배달 Fulfillment'],
  ['area:personalization', '0e8a16', '개인화·광고'],
  ['risk:money', 'd93f0b', '금액·원장·대사 위험'],
  ['risk:concurrency', 'd93f0b', '경합·중복·락 위험'],
  ['risk:external-provider', 'd93f0b', '외부 Provider 장애 위험'],
  ['risk:privacy', 'd93f0b', '개인정보·민감정보 위험'],
  ['risk:data-consistency', 'd93f0b', '이벤트·Read Model 정합성 위험'],
];

const milestoneDefinitions = [
  { title: 'R1 — 주문 진입·매장 운영', description: '회원·매장·검색·슬롯·재고·프로모션·매장 주문 처리까지 주문 진입과 현장 운영 흐름을 완성한다.', due_on: '2026-08-16T14:59:59Z' },
  { title: 'R2 — 이벤트·알림·로열티·환불', description: '영속 이벤트 전달, 알림, 포인트 적립·만료, 결제수단과 취소·환불을 완성한다.', due_on: '2026-08-30T14:59:59Z' },
  { title: 'R3 — 정산·이의제기·분석', description: '완료 주문의 일별 정산, 조정 원장, 이의제기와 매출 Read Model을 완성한다.', due_on: '2026-09-13T14:59:59Z' },
  { title: 'R4 — 계약·보안·운영 품질', description: 'API 계약, 객체 수준 인가, 운영 재처리와 JPA·쿼리 품질을 제품 수준으로 강화한다.', due_on: '2026-09-27T14:59:59Z' },
  { title: 'R5 — 성능·장애·공개 릴리스', description: '동시성 비교, 부하·장애 실험, 관측과 재현 가능한 MVP 릴리스를 완료한다.', due_on: '2026-10-11T14:59:59Z' },
  { title: 'P1 — 대표 확장', description: '선불 지갑, POS, 점주 AI, Kafka 확장 중 제품 가치와 기술 근거가 있는 범위만 구현한다.', due_on: '2026-11-08T14:59:59Z' },
  { title: 'P2/P3 — Future Work', description: 'MVP 이후 검증할 배달·개인화·광고 장기 백로그다.', due_on: null },
];

function loadDefinitions() {
  const root = path.join(process.cwd(), '.github', 'roadmap');
  return ['r1.cjs', 'r2.cjs', 'r3-r5.cjs', 'extensions.cjs']
    .flatMap((file) => require(path.join(root, file)));
}

function bullets(items) {
  return (items && items.length ? items : ['해당 없음']).map((item) => `- ${item}`).join('\n');
}

function issueRef(issue) {
  return `#${issue.number}`;
}

function renderTask(epic, task, parentIssue, dependencyIssues) {
  const dependencies = dependencyIssues.length
    ? dependencyIssues.map((issue) => `- ${issueRef(issue)} ${issue.title}`).join('\n')
    : '- 선행 이슈 없음';
  return `${MARKER(`${epic.key}-${task.key}`)}

## 상위 에픽

- ${issueRef(parentIssue)} ${parentIssue.title}
- 마일스톤: \`${epic.milestone}\`

## 변경 목적

${task.goal}

## 현재 소스 기준

${bullets(epic.sources.map((source) => `\`${source}\``))}

## 반드시 지켜야 할 규칙

${bullets(epic.invariants)}

## 구현 범위

${bullets(task.work)}

## 예상 변경 위치

${bullets((task.files && task.files.length ? task.files : epic.files).map((file) => `\`${file}\``))}

## 선행 관계

${dependencies}

## 완료 조건

${bullets(task.acceptance)}

## 필수 테스트와 검증

${bullets(task.tests)}

## 문서 갱신

${bullets(task.docs || epic.docs)}

## 공통 검증 명령

\`\`\`bash
./gradlew clean build
bash scripts/verify-docs.sh
git diff --check
\`\`\`

실행하지 못한 검증은 \`Not run\`으로, 실패한 검증은 원인과 영향 범위와 함께 보고한다. 비교 가능한 측정 없이 성능 개선을 주장하지 않는다.
`;
}

function renderEpic(epic, taskIssues, dependencyIssues) {
  const tasks = taskIssues.length
    ? taskIssues.map((issue) => `- [ ] ${issueRef(issue)} ${issue.title}`).join('\n')
    : '- 하위 작업 생성 예정';
  const dependencies = dependencyIssues.length
    ? dependencyIssues.map((issue) => `- ${issueRef(issue)} ${issue.title}`).join('\n')
    : '- 선행 에픽 없음';
  return `${MARKER(epic.key)}

## 목표

${epic.goal}

## 현재 기준선

${bullets(epic.sources.map((source) => `\`${source}\``))}

## 도메인 불변식·실패 의미론

${bullets(epic.invariants)}

## 영향 범위

- 모듈/영역: ${epic.areas.map((area) => `\`${area.replace('area:', '')}\``).join(', ')}
${bullets(epic.files.map((file) => `\`${file}\``))}

## 선행 에픽

${dependencies}

## 하위 작업

${tasks}

## 에픽 완료 조건

${bullets(epic.done)}

## 근거 문서

${bullets(epic.docs)}

이 에픽은 상위 목적을 위한 진행 단위다. 실제 구현은 하위 작업 이슈별로 ExecPlan·테스트·문서 갱신을 함께 수행한다.
`;
}

function renderRoadmap(epicsByMilestone, nativeLinkResult) {
  const sections = milestoneDefinitions.map((milestone) => {
    const issues = epicsByMilestone.get(milestone.title) || [];
    const checklist = issues.map((issue) => `- [ ] ${issueRef(issue)} ${issue.title}`).join('\n') || '- 등록된 에픽 없음';
    return `## ${milestone.title}\n\n${milestone.description}\n\n${checklist}`;
  }).join('\n\n');
  return `${MARKER(ROADMAP_KEY)}

# BeanFlow 개발 로드맵

현재 저장소의 구현과 문서를 기준으로 남은 작업을 에픽과 하위 작업으로 관리한다. 주문 생성·네 자원 예약·5분 lease·멱등 결제·UNKNOWN reconciliation·늦은 승인 void/refund는 완료된 기준선으로 취급하며 중복 구현하지 않는다.

## 운영 규칙

- 제품 정책은 \`docs/product/business-policy-decisions.md\`가 원본이다.
- 구조적 결정은 \`docs/adr/\`, 복잡한 Feature는 \`docs/exec-plans/active/\`에 기록한다.
- Controller는 Repository를 직접 호출하지 않고, Aggregate 간 참조는 ID를 우선한다.
- 외부 Provider 호출은 장시간 DB 트랜잭션 밖에서 실행한다.
- 실패를 local/in-memory/fake/no-op fallback으로 숨기지 않는다.
- H2가 아니라 PostgreSQL Testcontainers로 매핑·제약·락을 검증한다.

${sections}

## 계층 연결 상태

- Native sub-issue 연결 성공: ${nativeLinkResult.success}
- 이미 연결됐거나 중복으로 건너뜀: ${nativeLinkResult.skipped}
- Native 연결 실패: ${nativeLinkResult.failed}
- Native 기능이 보이지 않는 경우에도 각 에픽의 체크리스트 링크를 계층의 보조 표현으로 사용한다.
`;
}

async function ensureLabels(github, owner, repo) {
  for (const [name, color, description] of labels) {
    try {
      await github.rest.issues.getLabel({ owner, repo, name });
      await github.rest.issues.updateLabel({ owner, repo, name, new_name: name, color, description });
    } catch (error) {
      if (error.status !== 404) throw error;
      await github.rest.issues.createLabel({ owner, repo, name, color, description });
    }
  }
}

async function ensureMilestones(github, owner, repo) {
  const existing = await github.paginate(github.rest.issues.listMilestones, { owner, repo, state: 'all', per_page: 100 });
  const result = new Map();
  for (const definition of milestoneDefinitions) {
    let milestone = existing.find((candidate) => candidate.title === definition.title);
    if (!milestone) {
      milestone = (await github.rest.issues.createMilestone({ owner, repo, ...definition })).data;
    } else {
      milestone = (await github.rest.issues.updateMilestone({
        owner,
        repo,
        milestone_number: milestone.number,
        title: definition.title,
        description: definition.description,
        due_on: definition.due_on,
        state: 'open',
      })).data;
    }
    result.set(definition.title, milestone);
  }
  return result;
}

async function listRoadmapIssues(github, owner, repo) {
  const issues = await github.paginate(github.rest.issues.listForRepo, { owner, repo, state: 'all', per_page: 100 });
  return issues.filter((issue) => !issue.pull_request && issue.body && issue.body.includes('beanflow-roadmap-key:'));
}

function findByKey(issues, key) {
  const marker = MARKER(key);
  return issues.find((issue) => issue.body && issue.body.includes(marker));
}

async function upsertIssue(github, owner, repo, existingIssues, key, values) {
  const existing = findByKey(existingIssues, key);
  if (existing) {
    const updated = (await github.rest.issues.update({
      owner,
      repo,
      issue_number: existing.number,
      title: values.title,
      body: values.body,
      labels: values.labels,
      milestone: values.milestone,
      state: 'open',
    })).data;
    const index = existingIssues.findIndex((issue) => issue.number === existing.number);
    existingIssues[index] = updated;
    return updated;
  }
  const created = (await github.rest.issues.create({ owner, repo, ...values })).data;
  existingIssues.push(created);
  return created;
}

async function addSubIssue(github, parent, child, result) {
  try {
    await github.graphql(`
      mutation($issueId: ID!, $subIssueId: ID!) {
        addSubIssue(input: { issueId: $issueId, subIssueId: $subIssueId }) {
          issue { id number }
          subIssue { id number }
        }
      }
    `, { issueId: parent.node_id, subIssueId: child.node_id });
    result.success += 1;
  } catch (error) {
    const message = String(error.message || error);
    if (/already|parent|duplicate/i.test(message)) {
      result.skipped += 1;
    } else {
      result.failed += 1;
      console.warn(`Native sub-issue link failed ${parent.number} -> ${child.number}: ${message}`);
    }
  }
}

module.exports = async function run({ github, context, core }) {
  const { owner, repo } = context.repo;
  const epics = loadDefinitions();
  const milestoneMap = await ensureMilestones(github, owner, repo);
  await ensureLabels(github, owner, repo);
  const existingIssues = await listRoadmapIssues(github, owner, repo);
  const nativeLinkResult = { success: 0, skipped: 0, failed: 0 };

  let roadmap = await upsertIssue(github, owner, repo, existingIssues, ROADMAP_KEY, {
    title: '[Roadmap] BeanFlow 개발 로드맵',
    body: `${MARKER(ROADMAP_KEY)}\n\n에픽과 하위 작업을 생성하고 있습니다.`,
    labels: ['type:roadmap', 'priority:P0', 'area:platform'],
  });

  const epicIssues = new Map();
  for (const epic of epics) {
    const dependencies = (epic.dependsOn || []).map((key) => epicIssues.get(key)).filter(Boolean);
    const milestone = milestoneMap.get(epic.milestone);
    const issue = await upsertIssue(github, owner, repo, existingIssues, epic.key, {
      title: `[${epic.key}] ${epic.title}`,
      body: renderEpic(epic, [], dependencies),
      labels: ['type:epic', epic.priority, ...epic.areas, ...(epic.risks || [])],
      milestone: milestone.number,
    });
    epicIssues.set(epic.key, issue);
  }

  const taskIssuesByEpic = new Map();
  for (const epic of epics) {
    const parent = epicIssues.get(epic.key);
    const dependencies = (epic.dependsOn || []).map((key) => epicIssues.get(key)).filter(Boolean);
    const milestone = milestoneMap.get(epic.milestone);
    const taskIssues = [];
    for (const task of epic.tasks) {
      const issue = await upsertIssue(github, owner, repo, existingIssues, `${epic.key}-${task.key}`, {
        title: `[${epic.key}/${task.key}] ${task.title}`,
        body: renderTask(epic, task, parent, dependencies),
        labels: [task.type || 'type:task', epic.priority, ...epic.areas, ...(epic.risks || []), ...(task.labels || [])],
        milestone: milestone.number,
      });
      taskIssues.push(issue);
      await addSubIssue(github, parent, issue, nativeLinkResult);
    }
    taskIssuesByEpic.set(epic.key, taskIssues);
    await github.rest.issues.update({
      owner,
      repo,
      issue_number: parent.number,
      body: renderEpic(epic, taskIssues, dependencies),
    });
  }

  const epicsByMilestone = new Map();
  for (const epic of epics) {
    if (!epicsByMilestone.has(epic.milestone)) epicsByMilestone.set(epic.milestone, []);
    epicsByMilestone.get(epic.milestone).push(epicIssues.get(epic.key));
    await addSubIssue(github, roadmap, epicIssues.get(epic.key), nativeLinkResult);
  }

  roadmap = (await github.rest.issues.update({
    owner,
    repo,
    issue_number: roadmap.number,
    body: renderRoadmap(epicsByMilestone, nativeLinkResult),
  })).data;

  await github.rest.issues.createComment({
    owner,
    repo,
    issue_number: roadmap.number,
    body: `로드맵 동기화 완료\n\n- 에픽: ${epics.length}\n- 작업: ${epics.reduce((sum, epic) => sum + epic.tasks.length, 0)}\n- Native sub-issue: 성공 ${nativeLinkResult.success}, 건너뜀 ${nativeLinkResult.skipped}, 실패 ${nativeLinkResult.failed}\n- 기준 커밋: \`${context.sha}\``,
  });

  core.summary
    .addHeading('BeanFlow roadmap bootstrap')
    .addRaw(`Roadmap issue: #${roadmap.number}\n`)
    .addRaw(`Epics: ${epics.length}\n`)
    .addRaw(`Tasks: ${epics.reduce((sum, epic) => sum + epic.tasks.length, 0)}\n`)
    .addRaw(`Native links: ${JSON.stringify(nativeLinkResult)}\n`)
    .write();

  const branch = context.ref.replace('refs/heads/', '');
  if (branch.startsWith('automation/bootstrap-roadmap-')) {
    try {
      await github.rest.git.deleteRef({ owner, repo, ref: `heads/${branch}` });
      console.log(`Deleted temporary branch ${branch}`);
    } catch (error) {
      console.warn(`Could not delete temporary branch ${branch}: ${error.message}`);
    }
  }
};
