import type { Meta, StoryObj } from "@storybook/react-vite";
import { Button } from "../core/Button";
import { InlineNotice } from "./InlineNotice";

const meta = { title: "Components/Feedback/InlineNotice", component: InlineNotice, tags: ["autodocs"], parameters: { a11y: { test: "error" } }, args: { title: "금액을 다시 확인해 주세요", description: "서버가 새 가격을 계산했습니다. 내용을 확인한 뒤 다시 진행해 주세요." } } satisfies Meta<typeof InlineNotice>;
export default meta;
type Story = StoryObj<typeof meta>;
export const Information: Story = {};
export const WarningWithAction: Story = { args: { tone: "warning", announce: "polite", action: <Button size="sm" variant="secondary">다시 계산</Button> } };
export const SafeFailureCopy: Story = { args: { tone: "danger", announce: "assertive", title: "요청을 처리하지 못했습니다", description: "잠시 후 다시 시도해 주세요." } };
