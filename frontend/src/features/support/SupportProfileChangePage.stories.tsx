import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
import { http, HttpResponse } from "msw";
import { SupportProfileChangePage } from "./SupportProfileChangeWorkspace";
const meta = { title: "Pages/Support/Profile change", component: SupportProfileChangePage, tags: ["autodocs"], parameters: { a11y: { test: "error" }, routing: { path: "/support/profile-changes/:profileChangeId", initialEntry: "/support/profile-changes/83000000-0000-4000-8000-000000000001" }, docs: { description: { component: "공유된 정정 ID의 현재 권한과 상태를 조회합니다. 읽을 권한이 없으면 원문과 변경 명령을 표시하지 않습니다." } }, msw: { handlers: [http.get("/api/v1/support/profile-changes/:id/workflow", () => HttpResponse.json({ code: "ACCESS_DENIED", correlationId: "PROFILE-SCOPE" }, { status: 403 }))] } } } satisfies Meta<typeof SupportProfileChangePage>;
export default meta; type Story = StoryObj<typeof meta>;
export const AccessDenied: Story = { play: async ({ canvas }) => { await expect(await canvas.findByText("문의 코드 PROFILE-SCOPE")).toBeVisible(); } };
