import type { Meta, StoryObj } from "@storybook/react-vite";
import { RootRedirect } from "./Shells";

const meta = {
  component: RootRedirect,
  tags: ["ai-generated"],
} satisfies Meta<typeof RootRedirect>;

export default meta;
type Story = StoryObj<typeof meta>;

export const RoleChoice: Story = {};
