import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
import { http, HttpResponse } from "msw";
import { SupportBreakGlassPage } from "./SupportBreakGlassWorkspace";
const meta = { title: "Pages/Support/Break glass", component: SupportBreakGlassPage, tags: ["autodocs"], parameters: { a11y: { test: "error" }, routing: { path: "/support/break-glass/:requestId", initialEntry: "/support/break-glass/85000000-0000-4000-8000-000000000001" }, docs: { description: { component: "긴급 요청 ID로 원문 없이 현재 승인 또는 독립 사후 검토 권한을 확인합니다." } }, msw: { handlers: [http.get("/api/v1/support/break-glass-requests/:id/workflow", () => HttpResponse.json({ code: "ACCESS_DENIED", correlationId: "BREAK-GLASS-SCOPE" }, { status: 403 }))] } } } satisfies Meta<typeof SupportBreakGlassPage>;
export default meta; type Story = StoryObj<typeof meta>;
export const AccessDenied: Story = { play: async ({ canvas }) => { await expect(await canvas.findByText("문의 코드 BREAK-GLASS-SCOPE")).toBeVisible(); } };
