import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
import { http, HttpResponse } from "msw";
import { SupportOrderActionPage } from "./SupportOrderActionWorkspace";
const requestId = "73000000-0000-4000-8000-000000000001";
const meta = { title: "Pages/Support/Order action review", component: SupportOrderActionPage, tags: ["autodocs"], parameters: { a11y: { test: "error" }, routing: { path: "/support/action-requests/:requestId", initialEntry: `/support/action-requests/${requestId}` }, msw: { handlers: [http.get("/api/v1/support/action-requests/:requestId/workflow", () => HttpResponse.json({ code: "ACCESS_DENIED", correlationId: "REQUEST-SCOPE" }, { status: 403 }))] }, docs: { description: { component: "공유받은 요청 주소에서 현재 승인안과 권한을 조회합니다. 권한이 없거나 조회가 실패하면 승인·실행 명령을 숨기고 명시적인 오류를 제공합니다. 요청 내용의 해시와 현재 버전을 다시 확인한 뒤 허용된 업무를 수행합니다." }, story: { inline: false, height: "900px" } } } } satisfies Meta<typeof SupportOrderActionPage>;
export default meta; type Story = StoryObj<typeof meta>;
export const PermissionRequired: Story = { play: async ({ canvas }) => { await expect(canvas.getByRole("heading", { name: "주문 변경 승인과 실행" })).toBeVisible(); await expect(await canvas.findByText("문의 코드 REQUEST-SCOPE")).toBeVisible(); await expect(canvas.queryByRole("button", { name: "확인한 주문 변경 실행" })).not.toBeInTheDocument(); } };
