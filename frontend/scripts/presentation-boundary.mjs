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
  "src/features/discovery/StoreCards.tsx",
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
  ".store-card", ".store-mark", ".store-thumbnail", ".store-copy", ".availability", ".store-state-copy",
];

const ACTIVE_REFERENCE_FILES = new Set([
  "src/features/auth/customer/AuthPages.tsx",
  "src/features/auth/customer/AuthPages.stories.tsx",
  "src/features/auth/merchant/MerchantAuthPages.tsx",
  "src/features/auth/merchant/MerchantAuthPages.stories.tsx",
  "src/features/customer/CouponWalletPage.tsx",
  "src/features/customer/CouponWalletPage.stories.tsx",
  "src/features/customer/FavoriteStoresPage.tsx",
  "src/features/customer/FavoriteStoresPage.stories.tsx",
  "src/features/loyalty/PointsPage.tsx",
  "src/features/loyalty/PointsPage.stories.tsx",
  "src/features/merchant/StoreDisputesPage.tsx",
  "src/features/merchant/StoreDisputesPage.stories.tsx",
  "src/features/merchant/StoreRegionPage.tsx",
  "src/features/merchant/StoreRegionPage.stories.tsx",
  "src/features/merchant/StoreSettlementsPage.tsx",
  "src/features/merchant/StoreSettlementsPage.stories.tsx",
  "src/features/operations/MerchantAccountsPage.tsx",
  "src/features/operations/MerchantAccountsPage.stories.tsx",
  "src/features/operations/OperationsPolicyPage.tsx",
  "src/features/operations/OperationsPolicyPage.stories.tsx",
  "src/features/ordering/CustomerOrdersPage.tsx",
  "src/features/ordering/CustomerOrdersPage.stories.tsx",
  "src/features/payment/PaymentResultPages.tsx",
  "src/features/payment/CustomerHelpPage.stories.tsx",
  "src/features/payment/PaymentFailPage.stories.tsx",
  "src/features/payment/PaymentSuccessPage.stories.tsx",
  "src/pages/console/ConsolePages.tsx",
  "src/pages/console/ConsolePages.stories.tsx",
  "src/pages/console/OpsOrderPage.stories.tsx",
  "src/presentation/AppShells.tsx",
  "src/presentation/AppShells.stories.tsx",
]);

const DESIGN_SYSTEM_OWNED_SELECTORS = [
  ".context-label",
  ".surface-card",
  ".icon-action",
  ".inline-note",
  ".customer-page",
  ".back-link",
  ".success-mark",
  ".pending-mark",
  ".failure-mark",
  ".form-error",
  ".form-footnote",
  ".console-page",
  ".narrow-console-page",
  ".panel-heading",
];

const CANONICAL_PATTERN_SELECTORS = [
  ".bf-page-heading",
];

const MERCHANT_CHROME_OWNER_SOURCE = "src/presentation/merchant-workspace/MerchantWorkspaceShell.tsx";
const MERCHANT_CHROME_OWNER_STYLE = "src/presentation/merchant-workspace/merchant-workspace.css";
const MERCHANT_CHROME_MARKER_PATTERN = /\bbf-merchant-(?:workspace|sidebar|topbar)\b/;
const SUPPORT_CHROME_OWNER_SOURCE = "src/presentation/support-workspace/SupportWorkspaceShell.tsx";
const SUPPORT_CHROME_OWNER_STYLE = "src/presentation/support-workspace/support-workspace.css";
const SUPPORT_CHROME_MARKER_PATTERN = /\bbf-support-(?:workspace|sidebar|topbar)\b/;
const OPERATIONS_CHROME_OWNER_SOURCE = "src/presentation/operations-workspace/OperationsWorkspaceShell.tsx";
const OPERATIONS_CHROME_OWNER_STYLE = "src/presentation/operations-workspace/operations-workspace.css";
const OPERATIONS_CHROME_MARKER_PATTERN = /\bbf-operations-(?:workspace|sidebar|topbar)\b/;
const WORKSPACE_FRAME_OWNER_SOURCE = "src/design-system/patterns/navigation/WorkspaceFrame.tsx";
const WORKSPACE_FRAME_OWNER_STYLE = "src/design-system/patterns/navigation/workspace-frame.css";
const WORKSPACE_FRAME_MARKER_PATTERN = /\bbf-workspace-frame(?:__\w+)?\b/;

const APPLICATION_LAYER_PREFIXES = [
  "src/api/",
  "src/auth/",
  "src/features/",
  "src/pages/",
  "src/presentation/",
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
  const supportCenterSource = normalizedFile.startsWith("src/presentation/support-center/")
    || normalizedFile === "src/features/support/SupportCenterRoutes.tsx"
    || normalizedFile === "src/features/support/SupportCenterRoutes.stories.tsx";
  const routerSource = normalizedFile === "src/router.tsx";
  const activeReferenceSource = ACTIVE_REFERENCE_FILES.has(normalizedFile) || supportCenterSource;
  if (!refreshSource && !routerSource && !activeReferenceSource) return [];

  const violations = [];
  for (const match of source.matchAll(IMPORT_PATTERN)) {
    const importPath = match[1];
    const normalized = importPath.startsWith(".")
      ? normalizedImport(normalizedFile, importPath)
      : importPath;

    if ((refreshSource || activeReferenceSource) && matchesLegacySharedPresentation(normalized)) {
      violations.push({ kind: "legacy-shared-presentation", importPath });
      continue;
    }
    if ((refreshSource || activeReferenceSource) && matchesOldGlobalStyles(normalized)) {
      violations.push({ kind: "old-global-styles", importPath });
      continue;
    }
    if ((refreshSource || (activeReferenceSource && !normalizedFile.endsWith(".stories.tsx"))) && (/\.stories(?:\.|$)/.test(importPath) || /(?:^|\/)fixtures?(?:\/|$)/.test(importPath))) {
      violations.push({ kind: "story-fixture", importPath });
      continue;
    }
    if ((refreshSource || activeReferenceSource) && matchesLegacyTarget(normalized)) {
      violations.push({ kind: "legacy-presentation", importPath });
      continue;
    }
    if (routerSource && matchesLegacyTarget(normalized)) {
      violations.push({ kind: "legacy-route", importPath });
    }
  }
  return violations;
}

export function findDesignSystemDependencyViolations(sourceFile, source) {
  const normalizedFile = sourceFile.replaceAll(path.sep, "/");
  if (!normalizedFile.startsWith("src/design-system/")) return [];

  const violations = [];
  for (const match of source.matchAll(IMPORT_PATTERN)) {
    const importPath = match[1];
    const normalized = importPath.startsWith(".")
      ? normalizedImport(normalizedFile, importPath)
      : importPath;
    if (APPLICATION_LAYER_PREFIXES.some((prefix) => normalized.startsWith(prefix))) {
      violations.push({ kind: "design-system-application-dependency", importPath });
    }
  }
  return violations;
}

const RAW_CONTROL_PATTERN = /<(input|select|textarea)\b/g;
const CONTROL_SELECTOR_PATTERN = /(^|[\s>+~,:])(?:input|select|textarea|button)(?=$|[\s>+~.#:\[\],])/;

export function findRawControlViolations(sourceFile, source) {
  const normalizedFile = sourceFile.replaceAll(path.sep, "/");
  if (normalizedFile.startsWith("src/design-system/")) return [];
  return [...source.matchAll(RAW_CONTROL_PATTERN)].map((match) => ({
    kind: "raw-product-control",
    element: match[1],
  }));
}

export function findParallelControlStyleViolations(sourceFile, styles) {
  const normalizedFile = sourceFile.replaceAll(path.sep, "/");
  if (normalizedFile.startsWith("src/design-system/")) return [];
  const violations = [];
  for (const match of styles.matchAll(/([^{}]+)\{/g)) {
    const selector = match[1].trim();
    if (selector.startsWith("@") || !CONTROL_SELECTOR_PATTERN.test(selector)) continue;
    violations.push({ kind: "parallel-control-css", selector });
  }
  return violations;
}

export function findCanonicalPatternStyleViolations(sourceFile, styles) {
  const normalizedFile = sourceFile.replaceAll(path.sep, "/");
  if (normalizedFile.startsWith("src/design-system/")) return [];

  const violations = [];
  for (const match of styles.matchAll(/([^{}]+)\{/g)) {
    const selectorGroup = match[1].trim();
    if (selectorGroup.startsWith("@")) continue;
    for (const selector of selectorGroup.split(",").map((item) => item.trim())) {
      if (CANONICAL_PATTERN_SELECTORS.some((canonical) => containsSelector(selector, canonical))) {
        violations.push({ kind: "canonical-pattern-css", selector });
      }
    }
  }
  return violations;
}

export function findMerchantChromeOwnershipViolations(sourceFile, source) {
  const normalizedFile = sourceFile.replaceAll(path.sep, "/");
  if (normalizedFile === MERCHANT_CHROME_OWNER_SOURCE || normalizedFile === MERCHANT_CHROME_OWNER_STYLE) return [];

  const violations = [];
  if (MERCHANT_CHROME_MARKER_PATTERN.test(source)) {
    violations.push({ kind: "merchant-chrome-outside-owner" });
  }
  if (normalizedFile === "src/presentation/AppShells.tsx" && !/<MerchantWorkspaceShell\b/.test(source)) {
    violations.push({ kind: "merchant-shell-composition-missing" });
  }
  return violations;
}

export function findSupportChromeOwnershipViolations(sourceFile, source) {
  const normalizedFile = sourceFile.replaceAll(path.sep, "/");
  if (normalizedFile === SUPPORT_CHROME_OWNER_SOURCE || normalizedFile === SUPPORT_CHROME_OWNER_STYLE) return [];

  const violations = [];
  if (SUPPORT_CHROME_MARKER_PATTERN.test(source)) {
    violations.push({ kind: "support-chrome-outside-owner" });
  }
  if (normalizedFile === "src/presentation/AppShells.tsx" && !/<SupportWorkspaceShell\b/.test(source)) {
    violations.push({ kind: "support-shell-composition-missing" });
  }
  return violations;
}

export function findOperationsChromeOwnershipViolations(sourceFile, source) {
  const normalizedFile = sourceFile.replaceAll(path.sep, "/");
  if (normalizedFile === OPERATIONS_CHROME_OWNER_SOURCE || normalizedFile === OPERATIONS_CHROME_OWNER_STYLE) return [];

  const violations = [];
  if (OPERATIONS_CHROME_MARKER_PATTERN.test(source)) {
    violations.push({ kind: "operations-chrome-outside-owner" });
  }
  if (normalizedFile === "src/presentation/AppShells.tsx" && !/<OperationsWorkspaceShell\b/.test(source)) {
    violations.push({ kind: "operations-shell-composition-missing" });
  }
  return violations;
}

export function findWorkspaceFrameOwnershipViolations(sourceFile, source) {
  const normalizedFile = sourceFile.replaceAll(path.sep, "/");
  if (normalizedFile === WORKSPACE_FRAME_OWNER_SOURCE || normalizedFile === WORKSPACE_FRAME_OWNER_STYLE) return [];
  return WORKSPACE_FRAME_MARKER_PATTERN.test(source) ? [{ kind: "workspace-frame-outside-owner" }] : [];
}

export function findLegacyArtifactViolations(existingFiles, styles) {
  const violations = REMOVED_TARGET_FILES.filter((file) => existingFiles.has(file)).map((file) => ({ kind: "legacy-file", file }));
  for (const selector of REMOVED_TARGET_SELECTORS) {
    if (definesSelector(styles, selector)) violations.push({ kind: "legacy-css", file: "src/styles.css", selector });
  }
  for (const selector of DESIGN_SYSTEM_OWNED_SELECTORS) {
    if (definesSelector(styles, selector)) violations.push({ kind: "parallel-shared-css", file: "src/styles.css", selector });
  }
  return violations;
}

function definesSelector(styles, selector) {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return new RegExp(`(?:^|})\\s*${escaped}(?:\\s*[,:{])`, "m").test(styles);
}

function containsSelector(selector, canonical) {
  const escaped = canonical.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return new RegExp(`${escaped}(?=$|[\\s>+~.#:\\[])`).test(selector);
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
    ...[...ACTIVE_REFERENCE_FILES].map((file) => path.join(frontendRoot, file)),
    ...collectFiles(path.join(frontendRoot, "src/presentation/beanflow-refresh"))
      .filter((file) => /\.(?:ts|tsx)$/.test(file) && !/\.stories\.tsx$/.test(file)),
    ...collectFiles(path.join(frontendRoot, "src/presentation/support-center"))
      .filter((file) => /\.(?:ts|tsx)$/.test(file)),
    path.join(frontendRoot, "src/features/support/SupportCenterRoutes.tsx"),
    path.join(frontendRoot, "src/features/support/SupportCenterRoutes.stories.tsx"),
  ].filter((file, index, files) => fs.existsSync(file) && files.indexOf(file) === index);
  const violations = candidates.flatMap((file) => {
    const relative = path.relative(frontendRoot, file);
    return findPresentationBoundaryViolations(relative, fs.readFileSync(file, "utf8"))
      .map((violation) => ({ file: relative, ...violation }));
  });
  const designSystemFiles = collectFiles(path.join(frontendRoot, "src/design-system"))
    .filter((file) => /\.(?:ts|tsx)$/.test(file) && !/\.stories\.tsx$/.test(file));
  violations.push(...designSystemFiles.flatMap((file) => {
    const relative = path.relative(frontendRoot, file);
    return findDesignSystemDependencyViolations(relative, fs.readFileSync(file, "utf8"))
      .map((violation) => ({ file: relative, ...violation }));
  }));
  const productSourceFiles = collectFiles(path.join(frontendRoot, "src"))
    .filter((file) => /\.tsx$/.test(file) && !/\.stories\.tsx$/.test(file));
  violations.push(...productSourceFiles.flatMap((file) => {
    const relative = path.relative(frontendRoot, file);
    const source = fs.readFileSync(file, "utf8");
    return [
      ...findRawControlViolations(relative, source),
      ...findMerchantChromeOwnershipViolations(relative, source),
      ...findSupportChromeOwnershipViolations(relative, source),
      ...findOperationsChromeOwnershipViolations(relative, source),
      ...findWorkspaceFrameOwnershipViolations(relative, source),
    ]
      .map((violation) => ({ file: relative, ...violation }));
  }));
  const productStyleFiles = collectFiles(path.join(frontendRoot, "src"))
    .filter((file) => /\.css$/.test(file));
  violations.push(...productStyleFiles.flatMap((file) => {
    const relative = path.relative(frontendRoot, file);
    const styles = fs.readFileSync(file, "utf8");
    return [
      ...findParallelControlStyleViolations(relative, styles),
      ...findCanonicalPatternStyleViolations(relative, styles),
      ...findMerchantChromeOwnershipViolations(relative, styles),
      ...findSupportChromeOwnershipViolations(relative, styles),
      ...findOperationsChromeOwnershipViolations(relative, styles),
      ...findWorkspaceFrameOwnershipViolations(relative, styles),
    ].map((violation) => ({ file: relative, ...violation }));
  }));
  const existingFiles = new Set(collectFiles(path.join(frontendRoot, "src")).map((file) => path.relative(frontendRoot, file).replaceAll(path.sep, "/")));
  violations.push(...findLegacyArtifactViolations(existingFiles, fs.readFileSync(path.join(frontendRoot, "src/styles.css"), "utf8")));

  if (violations.length > 0) {
    for (const violation of violations) {
      console.error(`${violation.file}: ${violation.kind}: ${violation.importPath ?? violation.selector ?? violation.element ?? "must be removed"}`);
    }
    process.exitCode = 1;
    return;
  }
  console.log(`presentation boundary: passed (legacy presentation imports 0 across ${candidates.length} active route/story/refresh sources)`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) run();
