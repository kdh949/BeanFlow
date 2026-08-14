import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
import { CustomerHomePage } from "./CustomerPages";

const meta = {
  component: CustomerHomePage,
  tags: ["ai-generated"],
  parameters: {
    routing: {
      path: "/app",
      initialEntry: "/app?lat=37.5665&lng=126.9780",
    },
  },
} satisfies Meta<typeof CustomerHomePage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const NearbyStores: Story = {
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("시청점")).toBeVisible();
  },
};

export const LocationRequired: Story = {
  parameters: {
    routing: {
      path: "/app",
      initialEntry: "/app",
    },
  },
};
