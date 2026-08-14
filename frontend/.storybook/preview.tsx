import type { Preview } from "@storybook/react-vite";
import { createMemoryRouter } from "react-router";
import { RouterProvider } from "react-router/dom";
import { mswLoader } from "msw-storybook-addon/csf3";
import "../src/design-system/styles.css";
import "../src/styles.css";
import { mswHandlers } from "./msw-handlers";

const preview: Preview = {
  decorators: [
    (Story, context) => {
      const routing = context.parameters["routing"] as { initialEntry?: string; path?: string } | undefined;
      const router = createMemoryRouter(
        [{ path: routing?.path ?? "*", element: <Story /> }],
        { initialEntries: [routing?.initialEntry ?? "/"] },
      );
      return <RouterProvider router={router} />;
    },
  ],
  loaders: [mswLoader()],
  async beforeEach({ msw }) {
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
      // 'todo' - show a11y violations in the test UI only
      // 'error' - fail CI on a11y violations
      // 'off' - skip a11y checks entirely
      test: "todo",
    },
  },
};

export default preview;
