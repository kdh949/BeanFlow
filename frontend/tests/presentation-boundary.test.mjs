import assert from "node:assert/strict";
import test from "node:test";

import {
  findCanonicalPatternStyleViolations,
  findDesignSystemDependencyViolations,
  findLegacyArtifactViolations,
  findMerchantChromeOwnershipViolations,
  findOperationsChromeOwnershipViolations,
  findSupportChromeOwnershipViolations,
  findWorkspaceFrameOwnershipViolations,
  findParallelControlStyleViolations,
  findPresentationBoundaryViolations,
  findRawControlViolations,
} from "../scripts/presentation-boundary.mjs";

test("keeps merchant sidebar and topbar under one presentation owner", () => {
  assert.deepEqual(
    findMerchantChromeOwnershipViolations(
      "src/presentation/merchant-workspace/MerchantWorkspaceShell.tsx",
      `<aside className="bf-merchant-sidebar" /><header className="bf-merchant-topbar" />`,
    ),
    [],
  );
  assert.deepEqual(
    findMerchantChromeOwnershipViolations(
      "src/pages/console/AnotherStoreShell.tsx",
      `<aside className="bf-merchant-sidebar" />`,
    ).map(({ kind }) => kind),
    ["merchant-chrome-outside-owner"],
  );
  assert.deepEqual(
    findMerchantChromeOwnershipViolations(
      "src/presentation/AppShells.tsx",
      `export function ConsoleShell() { return <div />; }`,
    ).map(({ kind }) => kind),
    ["merchant-shell-composition-missing"],
  );
});

test("keeps support sidebar and topbar under one presentation owner", () => {
  assert.deepEqual(
    findSupportChromeOwnershipViolations(
      "src/presentation/support-workspace/SupportWorkspaceShell.tsx",
      `<aside className="bf-support-sidebar" /><header className="bf-support-topbar" />`,
    ),
    [],
  );
  assert.deepEqual(
    findSupportChromeOwnershipViolations(
      "src/features/support/AnotherSupportShell.tsx",
      `<aside className="bf-support-sidebar" />`,
    ).map(({ kind }) => kind),
    ["support-chrome-outside-owner"],
  );
  assert.deepEqual(
    findSupportChromeOwnershipViolations(
      "src/presentation/AppShells.tsx",
      `export function ConsoleShell() { return <div />; }`,
    ).map(({ kind }) => kind),
    ["support-shell-composition-missing"],
  );
});

test("keeps operations sidebar and topbar under one presentation owner", () => {
  assert.deepEqual(
    findOperationsChromeOwnershipViolations(
      "src/presentation/operations-workspace/OperationsWorkspaceShell.tsx",
      `<aside className="bf-operations-sidebar" /><header className="bf-operations-topbar" />`,
    ),
    [],
  );
  assert.deepEqual(
    findOperationsChromeOwnershipViolations(
      "src/pages/console/AnotherOperationsShell.tsx",
      `<aside className="bf-operations-sidebar" />`,
    ).map(({ kind }) => kind),
    ["operations-chrome-outside-owner"],
  );
  assert.deepEqual(
    findOperationsChromeOwnershipViolations(
      "src/presentation/AppShells.tsx",
      `export function ConsoleShell() { return <div />; }`,
    ).map(({ kind }) => kind),
    ["operations-shell-composition-missing"],
  );
});

test("keeps workspace frame selectors inside the design-system owner", () => {
  assert.deepEqual(
    findWorkspaceFrameOwnershipViolations(
      "src/design-system/patterns/navigation/WorkspaceFrame.tsx",
      `<div className="bf-workspace-frame"><main className="bf-workspace-frame__content" /></div>`,
    ),
    [],
  );
  assert.deepEqual(
    findWorkspaceFrameOwnershipViolations(
      "src/presentation/CopyFrame.tsx",
      `<div className="bf-workspace-frame" />`,
    ).map(({ kind }) => kind),
    ["workspace-frame-outside-owner"],
  );
});

test("allows presentation-neutral dependencies", () => {
  assert.deepEqual(
    findPresentationBoundaryViolations(
      "src/presentation/beanflow-refresh/CustomerHomePage.tsx",
      `import { customerApi } from "../../api/customerClient";
       import { useResource } from "../../features/shared/useResource";`,
    ),
    [],
  );
});

test("rejects legacy target pages and story fixtures", () => {
  const violations = findPresentationBoundaryViolations(
    "src/presentation/beanflow-refresh/CustomerHomePage.tsx",
    `import { CustomerHomePage } from "../../features/discovery/HomePage";
     import { homeHandlers } from "../../features/discovery/HomePage.stories";`,
  );

  assert.deepEqual(violations.map(({ kind }) => kind), ["legacy-presentation", "story-fixture"]);
});

test("allows canonical design-system and rejects retired shared presentation", () => {
  const violations = findPresentationBoundaryViolations(
    "src/presentation/beanflow-refresh/RefreshShared.tsx",
    `import { Button, StatusText } from "../../design-system";
     import { CustomerShell } from "../../components/Shells";
     import { FormField } from "../../components/Ui";
     import "../../styles.css";`,
  );

  assert.deepEqual(
    violations.map(({ kind }) => kind),
    ["legacy-shared-presentation", "legacy-shared-presentation", "old-global-styles"],
  );
});

test("rejects target legacy route imports", () => {
  const violations = findPresentationBoundaryViolations(
    "src/router.tsx",
    `import { CheckoutPage } from "./features/payment/CheckoutPage";
     import { CustomerLoginPage } from "./features/auth/customer/AuthPages";`,
  );

  assert.deepEqual(violations.map(({ kind }) => kind), ["legacy-route"]);
});

test("rejects legacy presentation imports from active reference pages and stories", () => {
  assert.deepEqual(
    findPresentationBoundaryViolations(
      "src/features/customer/FavoriteStoresPage.tsx",
      `import { StoreCard } from "../discovery/StoreCards";`,
    ).map(({ kind }) => kind),
    ["legacy-presentation"],
  );
  assert.deepEqual(
    findPresentationBoundaryViolations(
      "src/features/customer/FavoriteStoresPage.stories.tsx",
      `import { customerStore } from "../../../.storybook/fixtures";
       import { StoreCard } from "../discovery/StoreCards";`,
    ).map(({ kind }) => kind),
    ["legacy-presentation"],
  );
});

test("auto-discovers the support-center presentation root and runtime routes", () => {
  assert.deepEqual(
    findPresentationBoundaryViolations(
      "src/presentation/support-center/AnotherScreen.tsx",
      `import { FormField } from "../../components/Ui";
       import "../../styles.css";`,
    ).map(({ kind }) => kind),
    ["legacy-shared-presentation", "old-global-styles"],
  );
  assert.deepEqual(
    findPresentationBoundaryViolations(
      "src/features/support/SupportCenterRoutes.tsx",
      `import { handlers } from "../../../.storybook/fixtures";`,
    ).map(({ kind }) => kind),
    ["story-fixture"],
  );
  assert.deepEqual(
    findPresentationBoundaryViolations(
      "src/features/support/SupportCenterRoutes.stories.tsx",
      `import { HttpResponse, http } from "msw";`,
    ),
    [],
  );
});

test("rejects retained target files and route-unused legacy CSS", () => {
  const violations = findLegacyArtifactViolations(
    new Set(["src/features/discovery/HomePage.tsx"]),
    ".home-page { padding: 0 }",
  );
  assert.deepEqual(violations.map(({ kind }) => kind), ["legacy-file", "legacy-css"]);
});

test("rejects application dependencies from the canonical design system", () => {
  const violations = findDesignSystemDependencyViolations(
    "src/design-system/patterns/feedback/ResourceState.tsx",
    `import { ApiRequestError } from "../../../api/client";
     import { Button } from "../../components/core/Button";`,
  );

  assert.deepEqual(violations.map(({ kind }) => kind), ["design-system-application-dependency"]);
});

test("rejects design-system-owned shared selectors in the global stylesheet", () => {
  const violations = findLegacyArtifactViolations(
    new Set(),
    ".context-label { color: red } .customer-page { padding: 1rem }",
  );

  assert.deepEqual(violations.map(({ kind }) => kind), ["parallel-shared-css", "parallel-shared-css"]);
});

test("allows native controls only inside the canonical design system", () => {
  assert.deepEqual(
    findRawControlViolations("src/design-system/components/forms/Field.tsx", "<input /><select /><textarea />"),
    [],
  );
  assert.deepEqual(
    findRawControlViolations("src/features/auth/LoginPage.tsx", "<input /><select /><textarea />").map(({ kind, element }) => ({ kind, element })),
    [
      { kind: "raw-product-control", element: "input" },
      { kind: "raw-product-control", element: "select" },
      { kind: "raw-product-control", element: "textarea" },
    ],
  );
});

test("rejects feature CSS that restyles native controls", () => {
  const violations = findParallelControlStyleViolations(
    "src/features/orders/orders.css",
    ".order-form input, .order-form select:focus { border: 1px solid red; } .order-actions button { min-height: 30px; }",
  );
  assert.deepEqual(violations.map(({ kind }) => kind), ["parallel-control-css", "parallel-control-css"]);
  assert.deepEqual(
    findParallelControlStyleViolations("src/design-system/components/forms/forms.css", ".bf-field input { min-height: 44px; }"),
    [],
  );
});

test("rejects product CSS that reaches into canonical PageHeading styles", () => {
  const violations = findCanonicalPatternStyleViolations(
    "src/presentation/beanflow-refresh/refresh.css",
    ".bfr-cart .bf-page-heading { margin-bottom: 6px; } .bfr-page .bf-page-heading h1 { font-size: 20px; }",
  );

  assert.deepEqual(
    violations.map(({ kind, selector }) => ({ kind, selector })),
    [
      { kind: "canonical-pattern-css", selector: ".bfr-cart .bf-page-heading" },
      { kind: "canonical-pattern-css", selector: ".bfr-page .bf-page-heading h1" },
    ],
  );
  assert.deepEqual(
    findCanonicalPatternStyleViolations(
      "src/design-system/patterns/layout/layout.css",
      ".bf-page-heading { margin-bottom: 24px; }",
    ),
    [],
  );
});
