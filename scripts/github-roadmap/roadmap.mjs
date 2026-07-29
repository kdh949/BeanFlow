import { labels, milestones, ROADMAP_VERSION } from './core.mjs';
import { r1Epics } from './definitions/r1.mjs';
import { r2Epics } from './definitions/r2.mjs';
import { r3Epics } from './definitions/r3.mjs';
import { r4Epics } from './definitions/r4.mjs';
import { r5Epics } from './definitions/r5.mjs';
import { futureEpics } from './definitions/future.mjs';

export const epics = [
  ...r1Epics,
  ...r2Epics,
  ...r3Epics,
  ...r4Epics,
  ...r5Epics,
  ...futureEpics,
];

export { labels, milestones, ROADMAP_VERSION };
