import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const TARGET_LEGACY_IMPORTS = [
  "features/discovery/HomePage",
  "features/discovery/StoreSearchPage",
  "features/discovery/StoreCards",
  "features/ordering/StoreDetailPage",
  "features/ordering/CartPage",
  "features/ordering/OrderPages",
  "features/payment/CheckoutPage",
  "features/merchant/StoreRefundPage",
  "pages/console/StoreOrderBoard",
  "pages/console/StoreOrderBoardView",
];

const REMOVED_TARGET_FILES = [
  "src/features/discovery/HomePage.tsx",
  "src/features/discovery/StoreSearchPage.tsx",
  "src/features/ordering/StoreDetailPage.tsx",
  "src/features/ordering/CartPage.tsx",
  "src/features/ordering/OrderPages.tsx",
  "src/features/payment/CheckoutPage.tsx",
  "src/features/merchant/StoreRefundPage.tsx",
  "src/pages/console/StoreOrderBoard.tsx",
  "src/pages/console/StoreOrderBoardView.tsx",
  "src/components/Ui.tsx",
  "src/components/Shells.tsx",
  "src/presentation/beanflow-refresh/RefreshPrimitives.tsx",
  "src/presentation/beanflow-refresh/RefreshFrames.tsx",
  "src/design-system/components/commerce/StatusBadge.tsx",
  "src/design-system/_ds_bundle.js",
  "src/design-system/_ds_manifest.json",
];

const REMOVED_TARGET_SELECTORS = [
  ".home-page", ".store-search-page", ".store-profile", ".cart-lines",
  ".checkout-card", ".customer-order-detail-page", ".store-board-page", ".refund-lines",
];

const IMPORT_PATTERN = /(?:import|export)\s+(?:[\s\S]*?\s+from\s+)?["']([^"']+)["']/g;

function normalizedImport(sourceFile, importPath) {
  const fromDirectory = path.posix.dirname(sourceFile.replaceAll(path.sep, "/"));
  return path.posix.normalize(path.posix.join(fromDirectory, importPath));
}

function matchesLegacyTarget(normalized) {
  return TARGET_LEGACY_IMPORTS.some((target) =>
    normalized === `src/${target}` || normalized.startsWith(`src/${target}.`),
  );
}

function matchesLegacySharedPresentation(normalized) {
  return normalized === "src/components/Ui"
    || normalized.startsWith("src/components/Ui.")
    || normalized === "src/components/Shells"
    || normalized.startsWith("src/components/Shells.")
    || normalized === "src/presentation/beanflow-refresh/RefreshPrimitives"
    || normalized.startsWith("src/presentation/beanflow-refresh/RefreshPrimitives.")
    || normalized === "src/presentation/beanflow-refresh/RefreshFrames"
    || normalized.startsWith("src/presentation/beanflow-refresh/RefreshFrames.");
}

function matchesOldGlobalStyles(normalized) {
  return normalized === "src/styles.css";
}

export function findPresentationBoundaryViolations(sourceFile, source) {
  const normalizedFile = sourceFile.replaceAll(path.sep, "/");
  const refreshSource = normalizedFile.startsWith("src/presentation/beanflow-refresh/");
  const routerSource = normalizedFile === "src/router.tsx";
  if (!refreshSource && !routerSource) return [];

  const violations = [];
  for (const match of source.matchAll(IMPORT_PATTERN)) {
    const importPath = match[1];
    const normalized = importPath.startsWith(".")
      ? normalizedImport(normalizedFile, importPath)
      : importPath;

    if (refreshSource && matchesLegacySharedPresentation(normalized)) {
      violations.push({ kind: "legacy-shared-presentation", importPath });
      continue;
    }
    if (refreshSource && matchesOldGlobalStyles(normalized)) {
      violations.push({ kind: "old-global-styles", importPath });
      continue;
    }
    if (refreshSource && (/\.stories(?:\.|$)/.test(importPath) || /(?:^|\/)fixtures?(?:\/|$)/.test(importPath))) {
      violations.push({ kind: "story-fixture", importPath });
      continue;
    }
    if (refreshSource && matchesLegacyTarget(normalized)) {
      violations.push({ kind: "legacy-presentation", importPath });
      continue;
    }
    if (routerSource && matchesLegacyTarget(normalized)) {
      violations.push({ kind: "legacy-route", importPath });
    }
  }
  return violations;
}

export function findLegacyArtifactViolations(existingFiles, styles) {
  const violations = REMOVED_TARGET_FILES.filter((file) => existingFiles.has(file)).map((file) => ({ kind: "legacy-file", file }));
  for (const selector of REMOVED_TARGET_SELECTORS) {
    if (styles.includes(selector)) violations.push({ kind: "legacy-css", file: "src/styles.css", selector });
  }
  return violations;
}

function collectFiles(directory) {
  if (!fs.existsSync(directory)) return [];
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const target = path.join(directory, entry.name);
    return entry.isDirectory() ? collectFiles(target) : [target];
  });
}

function run() {
  const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
  const candidates = [
    path.join(frontendRoot, "src/router.tsx"),
    ...collectFiles(path.join(frontendRoot, "src/presentation/beanflow-refresh"))
      .filter((file) => /\.(?:ts|tsx)$/.test(file) && !/\.stories\.tsx$/.test(file)),
  ];
  const violations = candidates.flatMap((file) => {
    const relative = path.relative(frontendRoot, file);
    return findPresentationBoundaryViolations(relative, fs.readFileSync(file, "utf8"))
      .map((violation) => ({ file: relative, ...violation }));
  });
  const existingFiles = new Set(collectFiles(path.join(frontendRoot, "src")).map((file) => path.relative(frontendRoot, file).replaceAll(path.sep, "/")));
  violations.push(...findLegacyArtifactViolations(existingFiles, fs.readFileSync(path.join(frontendRoot, "src/styles.css"), "utf8")));

  if (violations.length > 0) {
    for (const violation of violations) {
      console.error(`${violation.file}: ${violation.kind}: ${violation.importPath ?? violation.selector ?? "must be removed"}`);
    }
    process.exitCode = 1;
    return;
  }
  console.log("presentation boundary: passed");
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) run();
