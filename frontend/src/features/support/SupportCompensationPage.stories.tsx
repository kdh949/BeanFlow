import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
import { http, HttpResponse } from "msw";
import { SupportCompensationPage } from "./SupportCompensationWorkspace";
const meta = { title: "Pages/Support/Compensation", component: SupportCompensationPage, tags: ["autodocs"], parameters: { a11y: { test: "error" }, routing: { path: "/support/compensations/:compensationRequestId", initialEntry: "/support/compensations/75000000-0000-4000-8000-000000000001" }, docs: { description: { component: "공유받은 보상 요청 주소에서 현재 권한과 고정된 혜택 조건을 조회합니다. 조회 실패 시 이전 조건으로 지급할 수 없습니다." } }, msw: { handlers: [http.get("/api/v1/support/compensations/:id/workflow", () => HttpResponse.json({ code: "ACCESS_DENIED", correlationId: "BENEFIT-SCOPE" }, { status: 403 }))] } } } satisfies Meta<typeof SupportCompensationPage>;
export default meta; type Story = StoryObj<typeof meta>;
export const ScopeDenied: Story = { play: async ({ canvas }) => { await expect(await canvas.findByText("문의 코드 BENEFIT-SCOPE")).toBeVisible(); await expect(canvas.queryByRole("button", { name: "확인한 보상 지급" })).not.toBeInTheDocument(); } };
