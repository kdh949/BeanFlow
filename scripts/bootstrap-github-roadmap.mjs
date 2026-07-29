#!/usr/bin/env node

import fs from 'node:fs/promises';
import process from 'node:process';
import { epics, labels, milestones, ROADMAP_VERSION } from './github-roadmap/roadmap.mjs';

const API_VERSION = '2026-03-10';
const DEFAULT_API_URL = 'https://api.github.com';
const MARKER_PATTERN = /<!-- beanflow-roadmap-key: ([^ ]+) -->/;

function parseArgs(argv) {
  const result = { mode: 'plan', repo: process.env.GITHUB_REPOSITORY ?? null, verbose: false };
  for (let index = 0; index < argv.length; index += 1) {
    const value = argv[index];
    if (value === '--mode') result.mode = argv[++index];
    else if (value === '--repo') result.repo = argv[++index];
    else if (value === '--verbose') result.verbose = true;
    else if (value === '--help' || value === '-h') result.help = true;
    else throw new Error(`Unknown argument: ${value}`);
  }
  if (!['plan', 'apply'].includes(result.mode)) throw new Error('--mode must be plan or apply');
  if (result.repo && !/^[^/]+\/[^/]+$/.test(result.repo)) throw new Error('--repo must be owner/name');
  return result;
}

function printHelp() {
  console.log(`Usage:
  node scripts/bootstrap-github-roadmap.mjs --mode plan --repo owner/repo
  GITHUB_TOKEN=... node scripts/bootstrap-github-roadmap.mjs --mode apply --repo owner/repo

Modes:
  plan   Validate and print the planned roadmap without GitHub mutations.
  apply  Upsert labels, milestones, roadmap/epic/task issues and native sub-issues.`);
}

const sleep = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));
const backtickList = (values) => values.map((value) => `- \`${value}\``).join('\n');
const bulletList = (values) => values.map((value) => `- ${value}`).join('\n');
const checkList = (values) => values.map((value) => `- [ ] ${value}`).join('\n');
const issueMarker = (key) => `<!-- beanflow-roadmap-key: ${key} -->`;

class GitHubApiError extends Error {
  constructor(status, message, body) {
    super(`GitHub API ${status}: ${message}`);
    this.status = status;
    this.body = body;
  }
}

class GitHubApi {
  constructor({ owner, repo, token, verbose = false }) {
    this.owner = owner;
    this.repo = repo;
    this.token = token;
    this.verbose = verbose;
    this.baseUrl = process.env.GITHUB_API_URL ?? DEFAULT_API_URL;
  }

  async request(method, path, body = undefined, { attempts = 6 } = {}) {
    const url = `${this.baseUrl}${path}`;
    for (let attempt = 1; attempt <= attempts; attempt += 1) {
      const headers = {
        Accept: 'application/vnd.github+json',
        'X-GitHub-Api-Version': API_VERSION,
        'User-Agent': 'beanflow-roadmap-bootstrap',
      };
      if (this.token) headers.Authorization = `Bearer ${this.token}`;
      if (body !== undefined) headers['Content-Type'] = 'application/json';

      if (this.verbose) console.log(`${method} ${path}`);
      const response = await fetch(url, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
      });
      const text = await response.text();
      let parsed = null;
      if (text) {
        try {
          parsed = JSON.parse(text);
        } catch {
          parsed = text;
        }
      }
      if (response.ok) return { data: parsed, headers: response.headers };

      const retryable = response.status === 403 || response.status === 429 || response.status >= 500;
      if (retryable && attempt < attempts) {
        const retryAfterSeconds = Number(response.headers.get('retry-after') ?? '0');
        const wait = retryAfterSeconds > 0 ? retryAfterSeconds * 1000 : attempt * 3000;
        console.warn(`GitHub API ${response.status}; retrying in ${wait}ms (${attempt}/${attempts})`);
        await sleep(wait);
        continue;
      }
      const message = parsed?.message ?? response.statusText ?? 'request failed';
      throw new GitHubApiError(response.status, message, parsed);
    }
    throw new Error(`Exhausted retries for ${method} ${path}`);
  }

  repoPath(suffix) {
    return `/repos/${encodeURIComponent(this.owner)}/${encodeURIComponent(this.repo)}${suffix}`;
  }

  async paginate(path, query = {}) {
    const items = [];
    for (let page = 1; ; page += 1) {
      const params = new URLSearchParams({ ...query, per_page: '100', page: String(page) });
      const response = await this.request('GET', `${path}?${params}`);
      const pageItems = Array.isArray(response.data) ? response.data : [];
      items.push(...pageItems);
      if (pageItems.length < 100) break;
    }
    return items;
  }
}

function validateDefinition() {
  const errors = [];
  const labelNames = new Set(labels.map(([name]) => name));
  const milestoneKeys = new Set(milestones.map((item) => item.key));
  const epicIds = new Set();
  const issueKeys = new Set(['ROADMAP']);

  if (epics.length !== 31) errors.push(`expected 31 epics, got ${epics.length}`);
  const taskCount = epics.reduce((sum, item) => sum + item.tasks.length, 0);
  if (taskCount !== 93) errors.push(`expected 93 tasks, got ${taskCount}`);

  for (const item of epics) {
    if (epicIds.has(item.id)) errors.push(`duplicate epic id ${item.id}`);
    epicIds.add(item.id);
    if (!milestoneKeys.has(item.milestone)) errors.push(`${item.id}: unknown milestone ${item.milestone}`);
    if (!labelNames.has(`priority:${item.priority}`)) errors.push(`${item.id}: missing priority label`);
    for (const area of item.areas) if (!labelNames.has(`area:${area}`)) errors.push(`${item.id}: missing area:${area}`);
    for (const risk of item.risks) if (!labelNames.has(`risk:${risk}`)) errors.push(`${item.id}: missing risk:${risk}`);
    if (item.tasks.length !== 3) errors.push(`${item.id}: expected 3 tasks`);

    const epicKey = `EPIC:${item.id}`;
    if (issueKeys.has(epicKey)) errors.push(`duplicate key ${epicKey}`);
    issueKeys.add(epicKey);

    for (const child of item.tasks) {
      const key = `TASK:${item.id}:${child.id}`;
      if (issueKeys.has(key)) errors.push(`duplicate key ${key}`);
      issueKeys.add(key);
      if (!labelNames.has(child.type)) errors.push(`${item.id}/${child.id}: missing ${child.type}`);
      if (child.files.length === 0 || child.steps.length < 3 || child.tests.length < 3) {
        errors.push(`${item.id}/${child.id}: incomplete actionable definition`);
      }
    }
  }
  if (errors.length) throw new Error(`Roadmap validation failed:\n- ${errors.join('\n- ')}`);
  return { epicCount: epics.length, taskCount, issueCount: issueKeys.size };
}

function milestoneTitle(key) {
  const item = milestones.find((milestone) => milestone.key === key);
  if (!item) throw new Error(`Unknown milestone ${key}`);
  return item.title;
}

function labelsForEpic(item) {
  return [
    'type:epic',
    `priority:${item.priority}`,
    ...item.areas.map((area) => `area:${area}`),
    ...item.risks.map((risk) => `risk:${risk}`),
  ];
}

function labelsForTask(item, child) {
  return [
    child.type,
    `priority:${item.priority}`,
    ...item.areas.map((area) => `area:${area}`),
    ...item.risks.map((risk) => `risk:${risk}`),
  ];
}

function epicBody(item, taskIssues = []) {
  const taskSection = taskIssues.length
    ? taskIssues.map((issue, index) => `- [ ] #${issue.number} ${item.tasks[index].title}`).join('\n')
    : item.tasks.map((child) => `- [ ] ${item.id}/${child.id} ${child.title}`).join('\n');

  return `# 목표

${item.title}을 현재 BeanFlow의 모듈 경계와 실패 의미론을 유지하면서 완성한다.

## 현재 소스 기준

${bulletList(item.currentSource)}

## 도메인 불변식

${bulletList(item.invariants)}

## 하위 작업

${taskSection}

## 구현 원칙

- 비단순 변경은 \`.agent/PLANS.md\`에 따라 ExecPlan을 먼저 작성한다.
- Controller는 Repository를 직접 호출하지 않고 Application Service가 트랜잭션을 조정한다.
- 다른 Aggregate는 ID로 참조하고 외부 Provider 호출을 장기 DB 트랜잭션에 넣지 않는다.
- 실패를 빈 값·성공·암묵적 local/in-memory/fake fallback으로 바꾸지 않는다.

## 완료 조건

- [ ] 모든 하위 작업이 완료되고 관련 정책·ADR·OpenAPI가 구현과 일치한다.
- [ ] PostgreSQL Testcontainers, API 계약, Modulith/ArchUnit 검증이 관련 범위에서 통과한다.
- [ ] 금액·동시성·외부 Provider 작업은 중복·재시도·장애 경로가 자동화 테스트로 보호된다.
- [ ] \`./gradlew clean build\`, \`bash scripts/verify-docs.sh\`, \`git diff --check\` 결과를 보고한다.

## 관련 결정

${bulletList(item.decisionRefs)}

## 로드맵 정의

- 버전: ${ROADMAP_VERSION}
- 우선순위: ${item.priority}
- 마일스톤: ${milestoneTitle(item.milestone)}`;
}

function taskBody(item, child, parentIssue) {
  const docs = [...new Set([...item.decisionRefs, ...child.docs, 'AGENTS.md', 'docs/testing/definition-of-done.md'])];
  return `# 변경 목적

${child.title}을 구현해 상위 에픽의 불변식과 완료 조건을 충족한다.

## 상위 에픽

- #${parentIssue.number} ${item.title}

## 현재 소스 기준

${bulletList(item.currentSource)}

## 반드시 지킬 규칙

${bulletList(item.invariants)}

## 구현 절차

${child.steps.map((value, index) => `${index + 1}. ${value}`).join('\n')}

## 예상 변경 파일

${backtickList(child.files)}

## 완료 조건

${checkList(child.steps)}
- [ ] 코드·DB 제약·오류 의미가 같은 불변식을 보호한다.
- [ ] 신규 공개 계약과 상태 변경은 OpenAPI·ADR/정책·ExecPlan에 반영한다.
- [ ] 자동화 검증 결과와 실행하지 못한 검증을 구분해 보고한다.

## 필수 테스트

${checkList(child.tests)}

## 검증 명령

\`\`\`bash
./gradlew clean build
bash scripts/verify-docs.sh
git diff --check
\`\`\`

## 관련 문서

${backtickList(docs)}

## 로드맵 정의

- 키: ${item.id}/${child.id}
- 버전: ${ROADMAP_VERSION}`;
}

function roadmapBody(epicIssues) {
  const milestoneRows = milestones
    .map((item) => `| ${item.title} | ${item.dueOn ? item.dueOn.slice(0, 10) : '기한 미정'} |`)
    .join('\n');
  const epicChecklist = epics
    .map((item) => {
      const issue = epicIssues.get(item.id);
      return issue ? `- [ ] #${issue.number} ${item.title}` : `- [ ] ${item.id} ${item.title}`;
    })
    .join('\n');

  return `# BeanFlow 개발 로드맵

현재 저장소의 주문 생성·가격 snapshot·네 자원 5분 lease·주문 멱등성·외부 결제 Tx1/Provider/Tx2·UNKNOWN reconciliation 구현을 회귀 기준으로 유지하고, 남은 거래 생명주기와 운영 품질을 단계적으로 완성한다.

## 현재 완료 기준선

- 주문 시점 메뉴·옵션·가격 snapshot과 쿠폰 후 포인트 배분
- 픽업 슬롯·재고·쿠폰·포인트 원자 예약과 5분 lease 만료
- 주문 생성 멱등성, AuditRecord, BENEFIT_ONLY 결제
- 외부 결제 승인 트랜잭션 분리와 명시 거절 보상
- 결제 UNKNOWN 조회 reconciliation, late approval void/refund, 5회 후 MANUAL_REVIEW

## 마일스톤

| 마일스톤 | 기한 |
|---|---|
${milestoneRows}

## 에픽

${epicChecklist}

## 운영 규칙

- 에픽 아래 작업 이슈를 완료하고, 행동 변경은 코드·테스트·API·ADR/정책을 같은 변경에서 갱신한다.
- 실제 측정 전에는 성능 개선 수치를 완료 증거로 사용하지 않는다.
- Kafka·Redis·MSA·Kubernetes는 필요성과 장애 정책을 측정·문서화한 뒤 도입한다.
- 외부 실패와 비동기 실패를 성공으로 위장하지 않고 UNKNOWN·RECONCILING·MANUAL_REVIEW 등 명시 상태로 남긴다.

## 검증

- 에픽 수: ${epics.length}
- 작업 수: ${epics.reduce((sum, item) => sum + item.tasks.length, 0)}
- 정의 버전: ${ROADMAP_VERSION}`;
}

function sameStringSet(actualValues, expectedValues) {
  const actual = [...actualValues].sort();
  const expected = [...expectedValues].sort();
  return actual.length === expected.length && actual.every((value, index) => value === expected[index]);
}

async function ensureLabels(api, counters) {
  const existing = await api.paginate(api.repoPath('/labels'));
  const byName = new Map(existing.map((item) => [item.name, item]));

  for (const [name, color, description] of labels) {
    const current = byName.get(name);
    if (!current) {
      await api.request('POST', api.repoPath('/labels'), { name, color, description });
      counters.labelsCreated += 1;
      await sleep(250);
      continue;
    }
    if (current.color.toLowerCase() !== color.toLowerCase() || (current.description ?? '') !== description) {
      await api.request('PATCH', api.repoPath(`/labels/${encodeURIComponent(name)}`), {
        new_name: name,
        color,
        description,
      });
      counters.labelsUpdated += 1;
      await sleep(200);
    }
  }
}

async function ensureMilestones(api, counters) {
  const existing = await api.paginate(api.repoPath('/milestones'), { state: 'all' });
  const byTitle = new Map(existing.map((item) => [item.title, item]));
  const result = new Map();

  for (const definition of milestones) {
    const current = byTitle.get(definition.title);
    if (!current) {
      const response = await api.request('POST', api.repoPath('/milestones'), {
        title: definition.title,
        description: definition.description,
        due_on: definition.dueOn,
      });
      result.set(definition.key, response.data.number);
      counters.milestonesCreated += 1;
      await sleep(300);
      continue;
    }

    const currentDue = current.due_on ?? null;
    if (current.description !== definition.description || currentDue !== definition.dueOn || current.state !== 'open') {
      const response = await api.request('PATCH', api.repoPath(`/milestones/${current.number}`), {
        description: definition.description,
        due_on: definition.dueOn,
        state: 'open',
      });
      result.set(definition.key, response.data.number);
      counters.milestonesUpdated += 1;
      await sleep(250);
    } else {
      result.set(definition.key, current.number);
    }
  }
  return result;
}

async function loadManagedIssues(api) {
  const items = await api.paginate(api.repoPath('/issues'), { state: 'all' });
  const byKey = new Map();
  for (const item of items) {
    if (item.pull_request) continue;
    const match = (item.body ?? '').match(MARKER_PATTERN);
    if (match) byKey.set(match[1], item);
  }
  return byKey;
}

async function upsertIssue({ api, managedIssues, counters, key, title, body, labelNames, milestoneNumber }) {
  const fullBody = `${issueMarker(key)}\n${body}`;
  const current = managedIssues.get(key);
  const expectedMilestone = milestoneNumber ?? null;

  if (!current) {
    const response = await api.request('POST', api.repoPath('/issues'), {
      title,
      body: fullBody,
      labels: labelNames,
      milestone: expectedMilestone,
    });
    const created = response.data;
    verifyIssueMetadata(created, key, labelNames, expectedMilestone);
    managedIssues.set(key, created);
    counters.issuesCreated += 1;
    await sleep(500);
    return created;
  }

  const currentLabels = (current.labels ?? []).map((label) => (typeof label === 'string' ? label : label.name));
  const needsUpdate =
    current.title !== title ||
    current.body !== fullBody ||
    !sameStringSet(currentLabels, labelNames) ||
    (current.milestone?.number ?? null) !== expectedMilestone;

  if (!needsUpdate) return current;
  const response = await api.request('PATCH', api.repoPath(`/issues/${current.number}`), {
    title,
    body: fullBody,
    labels: labelNames,
    milestone: expectedMilestone,
  });
  const updated = response.data;
  verifyIssueMetadata(updated, key, labelNames, expectedMilestone);
  managedIssues.set(key, updated);
  counters.issuesUpdated += 1;
  await sleep(350);
  return updated;
}

function verifyIssueMetadata(issue, key, expectedLabels, expectedMilestone) {
  const actualLabels = (issue.labels ?? []).map((label) => (typeof label === 'string' ? label : label.name));
  if (!sameStringSet(actualLabels, expectedLabels)) {
    throw new Error(`${key}: GitHub silently dropped labels. expected=${expectedLabels} actual=${actualLabels}`);
  }
  if ((issue.milestone?.number ?? null) !== expectedMilestone) {
    throw new Error(`${key}: GitHub silently dropped milestone ${expectedMilestone}`);
  }
}

async function listSubIssues(api, parentNumber) {
  return api.paginate(api.repoPath(`/issues/${parentNumber}/sub_issues`));
}

async function ensureSubIssue(api, parent, child, counters) {
  const existing = await listSubIssues(api, parent.number);
  if (existing.some((item) => item.id === child.id)) {
    counters.subIssuesExisting += 1;
    return;
  }
  await api.request('POST', api.repoPath(`/issues/${parent.number}/sub_issues`), {
    sub_issue_id: child.id,
  });
  counters.subIssuesCreated += 1;
  await sleep(500);
}

async function writeStepSummary(summary) {
  const target = process.env.GITHUB_STEP_SUMMARY;
  if (!target) return;
  await fs.appendFile(target, summary, 'utf8');
}

function summaryMarkdown(counters, roadmapIssue) {
  return `## BeanFlow roadmap bootstrap\n\n| 항목 | 생성 | 갱신/기존 |\n|---|---:|---:|\n| Labels | ${counters.labelsCreated} | ${counters.labelsUpdated} |\n| Milestones | ${counters.milestonesCreated} | ${counters.milestonesUpdated} |\n| Issues | ${counters.issuesCreated} | ${counters.issuesUpdated} |\n| Native sub-issues | ${counters.subIssuesCreated} | ${counters.subIssuesExisting} |\n\nRoadmap issue: #${roadmapIssue.number}\n`;
}

async function applyRoadmap(options) {
  if (!options.repo) throw new Error('--repo or GITHUB_REPOSITORY is required in apply mode');
  const token = process.env.GITHUB_TOKEN ?? process.env.GH_TOKEN;
  if (!token) throw new Error('GITHUB_TOKEN or GH_TOKEN with Issues write permission is required in apply mode');
  const [owner, repo] = options.repo.split('/');
  const api = new GitHubApi({ owner, repo, token, verbose: options.verbose });
  const counters = {
    labelsCreated: 0,
    labelsUpdated: 0,
    milestonesCreated: 0,
    milestonesUpdated: 0,
    issuesCreated: 0,
    issuesUpdated: 0,
    subIssuesCreated: 0,
    subIssuesExisting: 0,
  };

  console.log(`Applying BeanFlow roadmap ${ROADMAP_VERSION} to ${options.repo}`);
  await ensureLabels(api, counters);
  const milestoneNumbers = await ensureMilestones(api, counters);
  const managedIssues = await loadManagedIssues(api);

  const epicIssues = new Map();
  for (const item of epics) {
    const issue = await upsertIssue({
      api,
      managedIssues,
      counters,
      key: `EPIC:${item.id}`,
      title: `[Epic ${item.id}] ${item.title}`,
      body: epicBody(item),
      labelNames: labelsForEpic(item),
      milestoneNumber: milestoneNumbers.get(item.milestone),
    });
    epicIssues.set(item.id, issue);
  }

  const taskIssues = new Map();
  for (const item of epics) {
    const parent = epicIssues.get(item.id);
    const children = [];
    for (const child of item.tasks) {
      const issue = await upsertIssue({
        api,
        managedIssues,
        counters,
        key: `TASK:${item.id}:${child.id}`,
        title: `[${item.id}/${child.id}] ${child.title}`,
        body: taskBody(item, child, parent),
        labelNames: labelsForTask(item, child),
        milestoneNumber: milestoneNumbers.get(item.milestone),
      });
      children.push(issue);
    }
    taskIssues.set(item.id, children);

    const refreshedParent = await upsertIssue({
      api,
      managedIssues,
      counters,
      key: `EPIC:${item.id}`,
      title: `[Epic ${item.id}] ${item.title}`,
      body: epicBody(item, children),
      labelNames: labelsForEpic(item),
      milestoneNumber: milestoneNumbers.get(item.milestone),
    });
    epicIssues.set(item.id, refreshedParent);
  }

  const roadmapIssue = await upsertIssue({
    api,
    managedIssues,
    counters,
    key: 'ROADMAP',
    title: '[Roadmap] BeanFlow 개발 로드맵',
    body: roadmapBody(epicIssues),
    labelNames: ['type:roadmap', 'priority:P0', 'area:platform'],
    milestoneNumber: null,
  });

  for (const item of epics) {
    const epicIssue = epicIssues.get(item.id);
    await ensureSubIssue(api, roadmapIssue, epicIssue, counters);
    for (const child of taskIssues.get(item.id)) {
      await ensureSubIssue(api, epicIssue, child, counters);
    }
  }

  const refreshedManaged = await loadManagedIssues(api);
  const expectedManaged = 1 + epics.length + epics.reduce((sum, item) => sum + item.tasks.length, 0);
  if (refreshedManaged.size < expectedManaged) {
    throw new Error(`Postcondition failed: expected at least ${expectedManaged} managed issues, got ${refreshedManaged.size}`);
  }

  const expectedRelations = epics.length + epics.reduce((sum, item) => sum + item.tasks.length, 0);
  const actualRelations = counters.subIssuesCreated + counters.subIssuesExisting;
  if (actualRelations !== expectedRelations) {
    throw new Error(`Postcondition failed: expected ${expectedRelations} sub-issue relations, got ${actualRelations}`);
  }

  const summary = summaryMarkdown(counters, roadmapIssue);
  console.log(summary);
  await writeStepSummary(summary);
}

function planRoadmap(options, counts) {
  console.log(`BeanFlow roadmap ${ROADMAP_VERSION}`);
  console.log('Mode: plan (no GitHub mutations)');
  console.log(`Repository: ${options.repo ?? '(not required for plan)'}`);
  console.log(`Labels: ${labels.length}`);
  console.log(`Milestones: ${milestones.length}`);
  console.log(`Epics: ${counts.epicCount}`);
  console.log(`Tasks: ${counts.taskCount}`);
  console.log(`Managed issues: ${counts.issueCount}`);
  console.log('');
  for (const milestone of milestones) {
    const milestoneEpics = epics.filter((item) => item.milestone === milestone.key);
    console.log(`${milestone.title}: ${milestoneEpics.length} epics`);
    for (const item of milestoneEpics) console.log(`  - ${item.id} ${item.title} (${item.tasks.length} tasks)`);
  }
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  if (options.help) {
    printHelp();
    return;
  }
  const counts = validateDefinition();
  if (options.mode === 'plan') planRoadmap(options, counts);
  else await applyRoadmap(options);
}

main().catch((error) => {
  console.error(error.stack ?? error.message ?? String(error));
  process.exitCode = 1;
});
