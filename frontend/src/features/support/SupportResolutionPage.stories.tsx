import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
import { http, HttpResponse } from "msw";
import { SupportResolutionPage } from "./SupportResolutionWorkspace";
const meta = { title: "Pages/Support/Resolution", component: SupportResolutionPage, tags: ["autodocs"], parameters: { a11y: { test: "error" }, routing: { path: "/support/resolutions/:resolutionId", initialEntry: "/support/resolutions/73000000-0000-4000-8000-000000000001" }, docs: { description: { component: "수락 후 해결 건의 직접 주소입니다. 상담 권한이 없거나 회수되면 금융 처리 결과와 후속 명령을 표시하지 않고 현재 조회 오류를 안내합니다." } }, msw: { handlers: [http.get("/api/v1/support/post-acceptance-resolutions/:resolutionId", () => HttpResponse.json({ code: "ACCESS_DENIED", correlationId: "RESOLUTION-READ" }, { status: 403 }))] } } } satisfies Meta<typeof SupportResolutionPage>;
export default meta; type Story = StoryObj<typeof meta>;
export const PermissionRequired: Story = { play: async ({ canvas }) => { await expect(await canvas.findByText("문의 코드 RESOLUTION-READ")).toBeVisible(); await expect(canvas.queryByRole("button", { name: "미완료 단계 진행 요청" })).not.toBeInTheDocument(); } };
