import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";
import { HttpResponse, http, delay } from "msw";
import { SupportFollowUpRoute } from "./SupportFollowUpRoute";

const caseId = "a1000000-0000-4000-8000-000000000001";
const supportCase = { caseId, state: "IN_PROGRESS", priority: "NORMAL", assigneeId: "a7000000-0000-4000-8000-000000000001", version: 4, openedAt: "2026-08-23T09:00:00Z", subjectLinks: [] };
const item = { itemId: "a8000000-0000-4000-8000-000000000001", source: "ORDERING", type: "ORDER_STATE", state: "COMPLETED", summary: "ORDER_STATE:COMPLETED", amountKrw: 7500, occurredAt: "2026-08-23T09:30:00Z" };
const caseHandler = http.get("/api/v1/support/cases/:caseId", () => HttpResponse.json(supportCase));
const timelineHandler = http.get("/api/v1/support/cases/:caseId/timeline", ({ request }) => {
  const more = new URL(request.url).searchParams.has("cursor");
  return HttpResponse.json({ items: [more ? { ...item, itemId: "a8000000-0000-4000-8000-000000000002", summary: "CASE_STATE:OPEN", state: "OPEN", type: "CASE_STATE", source: "SUPPORT", amountKrw: null } : item], nextCursor: more ? null : "page-two" });
});
const meta = {
  title: "Pages/Support/Follow-up route",
  component: SupportFollowUpRoute,
  tags: ["autodocs"],
  parameters: {
    a11y: { test: "error" }, layout: "fullscreen", docs: { story: { inline: false, height: "880px" } },
    routing: { path: "/support/follow-up", initialEntry: `/support/follow-up?caseId=${caseId}`, surface: "support" },
    msw: { handlers: [caseHandler, timelineHandler] },
  },
} satisfies Meta<typeof SupportFollowUpRoute>;
export default meta;
type Story = StoryObj<typeof meta>;

export const LinkedCase: Story = {
  play: async ({ canvas }) => {
    await expect(await canvas.findByText(`상담 ID ${caseId}`)).toBeVisible();
    await expect(canvas.getByText("주문 픽업 완료")).toBeVisible();
    await expect(canvas.getByRole("link", { name: "상담 처리로 돌아가기" })).toHaveAttribute("href", `/support?caseId=${caseId}`);
    await expect(canvas.queryByText(/ORDERING|ORDER_STATE/)).not.toBeInTheDocument();
    await userEvent.click(canvas.getByRole("button", { name: "이력 더 보기" }));
    await expect(await canvas.findByText("상담 접수")).toBeVisible();
    await expect(canvas.getByText("주문 픽업 완료")).toBeVisible();
    await expect(canvas.queryByText(/ORDER_STATE:|CASE_STATE:/)).not.toBeInTheDocument();
    await expect(canvas.queryByRole("button", { name: "이력 더 보기" })).not.toBeInTheDocument();
  },
};
export const CaseRequired: Story = {
  parameters: { routing: { path: "/support/follow-up", initialEntry: "/support/follow-up", surface: "support" } },
  play: async ({ canvas }) => { await expect(await canvas.findByText("상담 건을 먼저 열어 주세요")).toBeVisible(); },
};
export const Unavailable: Story = {
  parameters: { msw: { handlers: [caseHandler, http.get("/api/v1/support/cases/:caseId/timeline", () => HttpResponse.json({ code: "DEPENDENCY_UNAVAILABLE" }, { status: 503 }))] } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("alert")).toBeVisible();
    await expect(canvas.queryByText("표시할 이력이 없습니다")).not.toBeInTheDocument();
    await expect(canvas.queryByText(`상담 ID ${caseId}`)).not.toBeInTheDocument();
  },
};
export const Loading: Story = {
  parameters: { msw: { handlers: [http.get("/api/v1/support/cases/:caseId", async () => { await delay("infinite"); return HttpResponse.json(supportCase); }), timelineHandler] } },
  play: async ({ canvas }) => { await expect(await canvas.findByText("상담 건과 이력을 불러오는 중")).toBeVisible(); },
};
export const EmptyTimeline: Story = {
  parameters: { msw: { handlers: [caseHandler, http.get("/api/v1/support/cases/:caseId/timeline", () => HttpResponse.json({ items: [], nextCursor: null }))] } },
  play: async ({ canvas }) => { await expect(await canvas.findByText("표시할 이력이 없습니다")).toBeVisible(); },
};
