import { readFileSync, readdirSync } from "node:fs";
import { dirname, join, relative, sep } from "node:path";
import { fileURLToPath } from "node:url";
import ts from "typescript";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const src = join(root, "src");
const tokenRoot = join(src, "design-system", "tokens");
const baselinePath = join(root, "design-adherence-baseline.json");

function filesUnder(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = join(directory, entry.name);
    return entry.isDirectory() ? filesUnder(path) : [path];
  });
}

function displayPath(path) {
  return relative(root, path).replaceAll("\\", "/");
}

function withoutComments(source) {
  return source.replace(/\/\*[\s\S]*?\*\//g, "");
}

function propertyName(node) {
  if (ts.isIdentifier(node) || ts.isStringLiteral(node)) return node.text;
  return undefined;
}

function objectProperty(object, name) {
  return object.properties.find((property) =>
    ts.isPropertyAssignment(property) && propertyName(property.name) === name,
  )?.initializer;
}

function objectLiteral(node) {
  if (!node) return undefined;
  let current = node;
  while (ts.isSatisfiesExpression(current) || ts.isAsExpression(current) || ts.isParenthesizedExpression(current)) {
    current = current.expression;
  }
  return ts.isObjectLiteralExpression(current) ? current : undefined;
}

function storyAnalysis(path, source) {
  const sourceFile = ts.createSourceFile(path, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  let meta;
  let mswConfigurationCount = 0;

  function visit(node) {
    if (ts.isVariableDeclaration(node) && ts.isIdentifier(node.name) && node.name.text === "meta" && node.initializer) {
      meta = objectLiteral(node.initializer);
    }
    if (ts.isPropertyAssignment(node) && propertyName(node.name) === "msw") mswConfigurationCount += 1;
    ts.forEachChild(node, visit);
  }
  visit(sourceFile);

  const parameters = objectLiteral(meta && objectProperty(meta, "parameters"));
  const docs = objectLiteral(parameters && objectProperty(parameters, "docs"));
  const story = objectLiteral(docs && objectProperty(docs, "story"));
  const inline = story && objectProperty(story, "inline");

  return {
    mswConfigurationCount,
    hasIframeIsolation: inline?.kind === ts.SyntaxKind.FalseKeyword,
  };
}

const tokenFiles = filesUnder(tokenRoot).filter((path) => path.endsWith(".css"));
const tokens = new Set();
for (const path of tokenFiles) {
  const source = withoutComments(readFileSync(path, "utf8"));
  for (const match of source.matchAll(/--([a-z0-9-]+)\s*:/gi)) tokens.add(match[1]);
}

const violations = [];
const pixelCounts = new Map();
const sourceFiles = filesUnder(src);
const cssFiles = sourceFiles.filter((path) => path.endsWith(".css") && !path.startsWith(tokenRoot));

for (const path of cssFiles) {
  const file = displayPath(path);
  const source = withoutComments(readFileSync(path, "utf8"));

  for (const match of source.matchAll(/var\(--([a-z0-9-]+)/gi)) {
    if (!tokens.has(match[1]) && match[1] !== "i") {
      violations.push({ rule: "undefined-token", file, value: `--${match[1]}` });
    }
  }
  for (const match of source.matchAll(/#[0-9a-f]{3,8}\b|(?:rgb|hsl)a?\([^)]*\)/gi)) {
    violations.push({ rule: "raw-color", file, value: match[0].toLowerCase() });
  }
  for (const match of source.matchAll(/font-family\s*:\s*([^;}]+)/gi)) {
    const value = match[1].trim();
    if (!value.startsWith("var(") && !["inherit", "initial"].includes(value)) {
      violations.push({ rule: "raw-font", file, value });
    }
  }
  for (const match of source.matchAll(/box-shadow\s*:\s*([^;}]+)/gi)) {
    const value = match[1].trim();
    if (!value.startsWith("var(") && value !== "none") {
      violations.push({ rule: "raw-shadow", file, value });
    }
  }
  for (const match of source.matchAll(/-?(?:\d+\.)?\d+px\b/gi)) {
    const key = `${file}|${match[0]}`;
    pixelCounts.set(key, (pixelCounts.get(key) ?? 0) + 1);
  }
}

for (const [key, count] of pixelCounts) {
  if (count < 3) continue;
  const [file, value] = key.split("|");
  violations.push({ rule: "repeated-raw-pixel", file, value, count });
}

for (const path of sourceFiles.filter((candidate) => /\.(?:ts|tsx|js|jsx)$/.test(candidate))) {
  if (path.endsWith("_ds_bundle.js")) continue;
  const file = displayPath(path);
  const source = readFileSync(path, "utf8");
  if (/_ds_(?:bundle|manifest)/.test(source)) violations.push({ rule: "generated-import", file, value: "generated artefact" });
  if (/style\s*=\s*\{\s*\{/.test(source)) violations.push({ rule: "inline-static-style", file, value: "style={{...}}" });
  if (file.startsWith("src/design-system/components/") && /(?:className|style)\??\s*:/.test(source)) {
    violations.push({ rule: "public-style-escape", file, value: "className/style prop" });
  }
}

const editableComponentSource = sourceFiles
  .filter((path) => path.includes(`${join("design-system", "components")}${sep}`) && /\.(?:ts|tsx)$/.test(path) && !path.endsWith(".stories.tsx"))
  .map((path) => readFileSync(path, "utf8"))
  .join("\n");
const canonicalStyleFamilies = new Set();
for (const path of cssFiles.filter((candidate) => candidate.includes(`${join("design-system", "components")}`))) {
  const source = withoutComments(readFileSync(path, "utf8"));
  for (const match of source.matchAll(/\.bf-([a-z0-9]+)(?:[-_:{.#\[]|$)/gi)) canonicalStyleFamilies.add(match[1]);
}
for (const family of canonicalStyleFamilies) {
  if (!editableComponentSource.includes(`bf-${family}`)) {
    violations.push({ rule: "orphan-component-style", file: "src/design-system/components", value: `bf-${family}` });
  }
}

const storyFiles = sourceFiles.filter((path) => path.endsWith(".stories.tsx"));
const storySource = storyFiles.map((path) => readFileSync(path, "utf8")).join("\n");
let isolatedNetworkDocs = 0;
for (const path of storyFiles) {
  const file = displayPath(path);
  const source = readFileSync(path, "utf8");
  const title = source.match(/title:\s*["']([^"']+)["']/)?.[1];
  if (!title || !/^(Foundations|Components|Patterns|Pages|Explorations)\//.test(title)) {
    violations.push({ rule: "story-taxonomy", file, value: title ?? "missing title" });
  }
  if (!source.includes('tags: ["autodocs"]') && !source.includes("tags: ['!manifest']")) {
    violations.push({ rule: "story-documentation", file, value: "missing autodocs or !manifest" });
  }
  const analysis = storyAnalysis(path, source);
  if (source.includes('tags: ["autodocs"]') && analysis.mswConfigurationCount > 1) {
    if (analysis.hasIframeIsolation) isolatedNetworkDocs += 1;
    else violations.push({
      rule: "autodocs-msw-isolation",
      file,
      value: "multiple MSW variants require parameters.docs.story.inline = false",
    });
  }
}

const routerSource = readFileSync(join(src, "router.tsx"), "utf8");
const routeComponents = new Set([...routerSource.matchAll(/element:\s*<([A-Z][A-Za-z0-9]+)/g)].map((match) => match[1]));
for (const component of routeComponents) {
  if (!storySource.includes(component)) violations.push({ rule: "route-story-coverage", file: "src/router.tsx", value: component });
}

const normalized = violations
  .map((violation) => ({ ...violation, count: violation.count ?? 1 }))
  .sort((left, right) => `${left.rule}|${left.file}|${left.value}`.localeCompare(`${right.rule}|${right.file}|${right.value}`));

if (process.argv.includes("--print-baseline")) {
  const repeatedRawPixel = Object.fromEntries(normalized.filter((item) => item.rule === "repeated-raw-pixel").map((item) => [`${item.file}|${item.value}`, item.count]));
  process.stdout.write(`${JSON.stringify({ repeatedRawPixel }, null, 2)}\n`);
  process.exit(0);
}

const baseline = JSON.parse(readFileSync(baselinePath, "utf8"));
const currentRepeatedRawPixel = new Map(
  normalized
    .filter((violation) => violation.rule === "repeated-raw-pixel")
    .map((violation) => [`${violation.file}|${violation.value}`, violation.count]),
);
const failures = normalized.filter((violation) => {
  if (violation.rule !== "repeated-raw-pixel") return true;
  return violation.count > (baseline.repeatedRawPixel?.[`${violation.file}|${violation.value}`] ?? 0);
});
for (const [key, count] of Object.entries(baseline.repeatedRawPixel ?? {})) {
  const currentCount = currentRepeatedRawPixel.get(key) ?? 0;
  if (currentCount < count) {
    const [file, value] = key.split("|");
    failures.push({ rule: "stale-baseline", file, value, count: currentCount });
  }
}

if (failures.length) {
  console.error("Design adherence failed:");
  for (const violation of failures) console.error(`- ${violation.rule}: ${violation.file}: ${violation.value} (${violation.count})`);
  process.exit(1);
}

const debt = normalized.filter((violation) => violation.rule === "repeated-raw-pixel");
console.log(`Design adherence passed: ${tokens.size} tokens, ${storyFiles.length} story files, ${routeComponents.size} route components.`);
console.log(`Canonical style ownership passed: ${canonicalStyleFamilies.size} component style families have editable TSX owners.`);
console.log(`Autodocs isolation passed: ${isolatedNetworkDocs} multi-state MSW docs use story iframes.`);
console.log(`Baseline debt: ${debt.length} repeated raw pixel value(s); new values or count increases fail.`);
