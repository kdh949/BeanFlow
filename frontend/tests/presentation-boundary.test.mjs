import assert from "node:assert/strict";
import test from "node:test";

import {
  findCanonicalPatternStyleViolations,
  findDesignSystemDependencyViolations,
  findLegacyArtifactViolations,
  findParallelControlStyleViolations,
  findPresentationBoundaryViolations,
  findRawControlViolations,
} from "../scripts/presentation-boundary.mjs";

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
