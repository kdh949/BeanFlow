import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";
import { http, HttpResponse } from "msw";
import { SupportInquiryDirectoryPage, SupportInquiryDetailPage } from "./SupportInquiryPages";
const id = "b1000000-0000-4000-8000-000000000001";
const caseId = "b3000000-0000-4000-8000-000000000001";
const inquiryTitle = "주문 처리 문의";
const inquiry = { inquiryId: id, title: inquiryTitle, category: "ORDER_STATUS", state: "RECEIVED", orderReference: null, version: 0, createdAt: "2026-09-10T10:00:00Z" };
const detail = { inquiry, messages: [{ id: "b2000000-0000-4000-8000-000000000001", author: "CUSTOMER", content: "주문 진행 상태를 확인하고 싶습니다.", createdAt: "2026-09-10T10:00:00Z" }], nextMessageCursor: null, canReply: true };
const meta = { title: "Pages/Support/Customer inquiries", component: SupportInquiryDirectoryPage, tags: ["autodocs"], parameters: { a11y: { test: "error" }, docs: { story: { inline: false, height: "900px" } }, msw: { handlers: [http.get("/api/v1/support/inquiries", () => HttpResponse.json({ items: [inquiry], nextCursor: null })), http.get("/api/v1/support/inquiries/:inquiryId", () => HttpResponse.json({ detail, caseId: null, caseVersion: null, canClaim: true, canReply: false }))] } } } satisfies Meta<typeof SupportInquiryDirectoryPage>;
export default meta; type Story = StoryObj<typeof meta>;
export const Queue: Story = { play: async ({ canvas }) => { await expect(await canvas.findByRole("link", { name: "주문 처리 문의" })).toHaveAttribute("href", `/support/inquiries/${id}`); } };
export const Claim: Story = { render: () => <SupportInquiryDetailPage />, parameters: { routing: { path: "/support/inquiries/:inquiryId", initialEntry: `/support/inquiries/${id}` } }, play: async ({ canvas, msw }) => { msw.use(http.post("/api/v1/support/inquiries/:inquiryId/claims", async ({ request }) => { expect(await request.json()).toEqual({ expectedVersion: 0 }); return HttpResponse.json({ inquiryId: id, caseId }); })); await userEvent.click(await canvas.findByRole("button", { name: "인수하여 상담 열기" })); await expect(await canvas.findByRole("link", { name: "연결된 상담 관리" })).toHaveAttribute("href", `/support/cases/${caseId}`); } };
export const PublicResponse: Story = { ...Claim, parameters: { ...Claim.parameters, msw: { handlers: [http.get("/api/v1/support/inquiries/:inquiryId", () => HttpResponse.json({ detail: { ...detail, inquiry: { ...inquiry, state: "IN_PROGRESS", version: 1 } }, caseId, caseVersion: 3, canClaim: false, canReply: true })), http.post("/api/v1/support/inquiries/:inquiryId/messages", async ({ request }) => { expect(await request.json()).toEqual({ expectedVersion: 1, expectedCaseVersion: 3, content: "주문을 확인하고 있습니다." }); return HttpResponse.json({ inquiryId: id, messageId: "b2000000-0000-4000-8000-000000000002" }, { status: 201 }); })] } }, play: async ({ canvas }) => { await userEvent.type(await canvas.findByLabelText("공개 답변"), "주문을 확인하고 있습니다."); await userEvent.click(canvas.getByRole("button", { name: "고객에게 답변 보내기" })); await expect(await canvas.findByText("메시지를 보냈습니다")).toBeVisible(); } };

export const ReadOnly: Story = {
  ...Claim,
  parameters: { ...Claim.parameters, msw: { handlers: [http.get("/api/v1/support/inquiries/:inquiryId", () => HttpResponse.json({ detail, caseId: null, caseVersion: null, canClaim: false, canReply: false }))] } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("heading", { name: inquiryTitle })).toBeVisible();
    await expect(canvas.queryByRole("button", { name: "인수하여 상담 열기" })).not.toBeInTheDocument();
    await expect(canvas.queryByLabelText("공개 답변")).not.toBeInTheDocument();
  },
};

export const LostClaim: Story = {
  ...Claim,
  play: async ({ canvas, msw }) => {
    let first: { key: string | null; body: unknown } | undefined;
    msw.use(http.post("/api/v1/support/inquiries/:inquiryId/claims", async ({ request }) => {
      first = { key: request.headers.get("Idempotency-Key"), body: await request.json() };
      return HttpResponse.error();
    }));
    await userEvent.click(await canvas.findByRole("button", { name: "인수하여 상담 열기" }));
    await expect(await canvas.findByText("인수 결과를 확인하지 못했습니다")).toBeVisible();
    await expect(canvas.getByRole("button", { name: "인수하여 상담 열기" })).toBeDisabled();
    await expect(canvas.getByRole("button", { name: "문의 새로고침" })).toBeDisabled();
    msw.use(http.post("/api/v1/support/inquiries/:inquiryId/claims", async ({ request }) => {
      expect({ key: request.headers.get("Idempotency-Key"), body: await request.json() }).toEqual(first);
      return HttpResponse.json({ inquiryId: id, caseId });
    }));
    await userEvent.click(canvas.getByRole("button", { name: "같은 요청 결과 확인" }));
    await expect(await canvas.findByRole("link", { name: "연결된 상담 관리" })).toHaveAttribute("href", `/support/cases/${caseId}`);
  },
};

export const StaleClaim: Story = {
  ...Claim,
  play: async ({ canvas, msw }) => {
    msw.use(http.post("/api/v1/support/inquiries/:inquiryId/claims", () => HttpResponse.json({ code: "RESOURCE_STATE_CONFLICT", message: "Inquiry or case state changed" }, { status: 409 })));
    await userEvent.click(await canvas.findByRole("button", { name: "인수하여 상담 열기" }));
    await expect(await canvas.findByRole("alert")).toBeVisible();
    await expect(canvas.queryByRole("link", { name: "연결된 상담 관리" })).not.toBeInTheDocument();
    msw.use(http.get("/api/v1/support/inquiries/:inquiryId", () => HttpResponse.json({ detail: { ...detail, inquiry: { ...inquiry, state: "OPEN", version: 1 } }, caseId, caseVersion: 0, canClaim: false, canReply: false })));
    await userEvent.click(canvas.getByRole("button", { name: "문의 새로고침" }));
    await expect(await canvas.findByRole("link", { name: "연결된 상담 관리" })).toHaveAttribute("href", `/support/cases/${caseId}`);
    await expect(canvas.queryByRole("button", { name: "인수하여 상담 열기" })).not.toBeInTheDocument();
  },
};
