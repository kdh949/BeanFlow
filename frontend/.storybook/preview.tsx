import { sb } from "storybook/test";
import type { Preview } from "@storybook/react-vite";
import { useEffect } from "react";
import { createMemoryRouter, Outlet } from "react-router";
import { RouterProvider } from "react-router/dom";
import { mswLoader } from "msw-storybook-addon/csf3";
import { ConsoleFrame } from "../src/presentation/ConsoleFrame";
import { ConsoleShell, CustomerShell } from "../src/presentation/AppShells";
import { authToken } from "../src/auth/session";
import { merchantSession } from "../src/features/auth/merchant/merchantSession";
import "../src/design-system/styles.css";
import "../src/styles.css";
import { mswHandlers } from "./msw-handlers";

sb.mock(import("../src/payment/toss.ts"), { spy: true });

const storybookA11yTestMode =
  import.meta.env["VITE_STORYBOOK_A11Y_TEST"] === "off" ? "off" : "error";

const preview: Preview = {
  decorators: [
    (Story, context) => {
      const routing = context.parameters["routing"] as {
        initialEntry?: string | { pathname: string; state?: unknown };
        path?: string;
        surface?: "customer" | "refresh-customer" | "refresh-store" | "store" | "ops" | "support";
      } | undefined;
      const router = createMemoryRouter(
        routing?.surface === "customer"
          ? [{ element: <CustomerShell />, children: [{ path: routing.path ?? "*", element: <Story /> }] }]
          : routing?.surface === "refresh-customer"
            ? [{ element: <CustomerShell />, children: [{ path: routing.path ?? "*", element: <Story /> }] }]
            : routing?.surface === "refresh-store" || routing?.surface === "store"
              ? [{ element: <StoreStoryShell />, children: [{ path: routing.path ?? "*", element: <Story /> }] }]
              : routing?.surface === "ops"
                ? [{ element: <ConsoleFrame kind="ops" access="authenticated" actorLabel="운영 담당자" onLogOut={async () => {}}><Outlet /></ConsoleFrame>, children: [{ path: routing.path ?? "*", element: <Story /> }] }]
                : routing?.surface === "support"
                  ? [{ element: <ConsoleFrame kind="support" access="authenticated" actorLabel="고객지원 담당자" onLogOut={async () => {}}><Outlet /></ConsoleFrame>, children: [{ path: routing.path ?? "*", element: <Story /> }] }]
          : [{ path: routing?.path ?? "*", element: <Story /> }],
        { initialEntries: [routing?.initialEntry ?? "/"] },
      );
      return <RouterProvider router={router} />;
    },
  ],
  loaders: [mswLoader()],
  async beforeEach({ msw }) {
    // Each story is a fresh browser task; re-entry inside one play keeps its command journal.
    for (const key of Object.keys(sessionStorage)) if (key.startsWith("beanflow.support-command.v1.")) sessionStorage.removeItem(key);
    authToken.set(`storybook.${btoa(JSON.stringify({ sub: "storybook-operator", iss: "storybook" }))}.fixture`);
    msw.use(...mswHandlers);
  },
  parameters: {
    controls: {
      matchers: {
       color: /(background|color)$/i,
       date: /Date$/i,
      },
    },

    a11y: {
      test: storybookA11yTestMode,
    },
  },
};

function StoreStoryShell() {
  useEffect(() => { void merchantSession.refresh(); }, []);
  return <ConsoleShell kind="store" />;
}

export default preview;
