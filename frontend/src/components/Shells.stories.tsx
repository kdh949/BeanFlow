import type { Meta, StoryObj } from "@storybook/react-vite";
import { ConsoleShell, CustomerShell, RootRedirect } from "./Shells";

const meta = {
  title: "Pages/Shared/RoleChoice",
  component: RootRedirect,
  tags: ["autodocs"],
  parameters: {
    docs: { description: { component: "BeanFlow의 고객·매장·운영 작업 공간 진입점을 선택하는 root page입니다." } },
  },
} satisfies Meta<typeof RootRedirect>;

export default meta;
type Story = StoryObj<typeof meta>;

export const RoleChoice: Story = {};

export const CustomerChrome: Story = {
  render: () => <CustomerShell />,
  parameters: {
    routing: { path: "/app", initialEntry: "/app" },
    docs: { description: { story: "고객 app의 header, auth 상태와 bottom navigation chrome입니다." } },
  },
};

export const StoreChrome: Story = {
  render: () => <ConsoleShell kind="store" />,
  parameters: {
    routing: { path: "/store", initialEntry: "/store" },
    docs: { description: { story: "매장 작업 공간의 navigation과 credential 상태 chrome입니다." } },
  },
};

export const OperationsChrome: Story = {
  render: () => <ConsoleShell kind="ops" />,
  parameters: {
    routing: { path: "/ops", initialEntry: "/ops" },
    docs: { description: { story: "운영 작업 공간의 navigation과 credential 상태 chrome입니다." } },
  },
};
