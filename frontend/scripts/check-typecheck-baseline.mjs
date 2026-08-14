import { spawnSync } from "node:child_process";

const result = spawnSync("npm", ["run", "typecheck"], { encoding: "utf8", shell: false });
const output = `${result.stdout ?? ""}${result.stderr ?? ""}`;
process.stdout.write(output);

if (result.status === 0) {
  console.error("Frontend typecheck is clean. Remove MD-2026-014 baseline and use npm run typecheck directly in CI.");
  process.exit(1);
}

const errors = output.split(/\r?\n/).filter((line) => /error TS\d+:/.test(line));
const expected = [
  { file: "src/pages/console/ConsolePages.tsx", count: 1 },
  { file: "src/pages/customer/CustomerPages.tsx", count: 2 },
];
const csrfPattern = /error TS2741: Property '\"X-BEANFLOW-CSRF\"' is missing/;

const allowed = errors.length === 3 && expected.every(({ file, count }) =>
  errors.filter((line) => line.startsWith(`${file}(`) && csrfPattern.test(line)).length === count,
);

if (!allowed) {
  console.error("Frontend typecheck differs from the three MD-2026-014 CSRF errors.");
  process.exit(1);
}

console.log("Frontend typecheck matched exactly three MD-2026-014 CSRF baseline errors; Plan 80/90 must remove this gate.");
