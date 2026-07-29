import assert from 'node:assert/strict';
import test from 'node:test';
import { epics, labels, milestones } from './roadmap.mjs';

const unique = (values) => new Set(values).size === values.length;

test('roadmap has the expected epic and task counts', () => {
  assert.equal(epics.length, 31);
  assert.equal(epics.reduce((sum, item) => sum + item.tasks.length, 0), 93);
  assert.ok(epics.every((item) => item.tasks.length === 3));
});

test('all identifiers and titles are unique', () => {
  assert.ok(unique(epics.map((item) => item.id)));
  assert.ok(unique(epics.map((item) => item.title)));
  const taskKeys = epics.flatMap((item) => item.tasks.map((task) => `${item.id}/${task.id}`));
  assert.ok(unique(taskKeys));
});

test('all references resolve to defined labels and milestones', () => {
  const labelNames = new Set(labels.map(([name]) => name));
  const milestoneKeys = new Set(milestones.map((item) => item.key));

  for (const item of epics) {
    assert.ok(milestoneKeys.has(item.milestone), `${item.id}: unknown milestone`);
    assert.ok(labelNames.has(`priority:${item.priority}`), `${item.id}: missing priority label`);
    for (const area of item.areas) assert.ok(labelNames.has(`area:${area}`), `${item.id}: missing area:${area}`);
    for (const risk of item.risks) assert.ok(labelNames.has(`risk:${risk}`), `${item.id}: missing risk:${risk}`);
    for (const child of item.tasks) assert.ok(labelNames.has(child.type), `${item.id}/${child.id}: missing ${child.type}`);
  }
});

test('every issue definition is actionable', () => {
  for (const item of epics) {
    assert.ok(item.currentSource.length >= 1, `${item.id}: current source missing`);
    assert.ok(item.invariants.length >= 1, `${item.id}: invariants missing`);
    assert.ok(item.decisionRefs.length >= 1, `${item.id}: decision refs missing`);
    for (const child of item.tasks) {
      assert.ok(child.files.length >= 1, `${item.id}/${child.id}: files missing`);
      assert.ok(child.steps.length >= 3, `${item.id}/${child.id}: steps must be concrete`);
      assert.ok(child.tests.length >= 3, `${item.id}/${child.id}: tests must cover risks`);
    }
  }
});
