import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent, waitFor } from "storybook/test";
import { http, HttpResponse } from "msw";
import MockDate from "mockdate";
import { SupportDataAccessWorkspace } from "./SupportDataAccessWorkspace";
import type { components } from "../../api/schema";
const grantId = "a6000000-0000-4000-8000-000000000001";
const caseId = "a1000000-0000-4000-8000-000000000001";
const grant: components["schemas"]["DataAccessGrantResource"] = { grantId, caseId, authorizationBasis: "SUPPORT_DIRECT", subjectLinkId: "a3000000-0000-4000-8000-000000000001", subjectType: "CUSTOMER", subjectId: "a2000000-0000-4000-8000-000000000001", purpose: "CONTACT_CONFIRMATION", fields: ["CUSTOMER_PRIMARY_PHONE"], risk: "SENSITIVE", state: "ACTIVE", maxReveals: 1, reservedReveals: 0, requestedAt: "2026-09-10T09:00:00Z", expiresAt: "2026-09-10T09:05:00Z", version: 2 };
const supportCase = { caseId, state: "IN_PROGRESS", subjectLinks: [{ linkId: grant.subjectLinkId, subjectId: grant.subjectId, subjectType: "CUSTOMER", display: { state: "AVAILABLE", label: "김*객" } }] };
const session: components["schemas"]["VerificationSessionResource"] = { sessionId: "a4000000-0000-4000-8000-000000000001", caseId, subjectLinkId: grant.subjectLinkId, subjectType: "CUSTOMER", subjectId: grant.subjectId, purpose: "CONTACT_CONFIRMATION", actionScope: "PERSONAL_DATA_REVEAL", requestedLevel: "ENHANCED", achievedLevel: "ENHANCED", state: "VERIFIED", invalidAttempts: 0, startedAt: "2026-09-10T09:00:00Z", expiresAt: "2026-09-10T09:15:00Z", version: 2, challenges: [] };
const meta = { title: "Patterns/Support/Data access", component: SupportDataAccessWorkspace, tags: ["autodocs"], args: { initialGrantId: grantId }, beforeEach: () => { MockDate.set("2026-09-10T09:04:00Z"); return () => MockDate.reset(); }, parameters: { a11y: { test: "error" }, routing: { path: "/support/data-access/:grantId", initialEntry: `/support/data-access/${grantId}` }, msw: { handlers: [http.get("/api/v1/support/data-access-grants/:grantId", () => HttpResponse.json({ grant, viewerRole: "REQUESTER" }))] }, docs: { story: { inline: false, height: "1000px" } } } } satisfies Meta<typeof SupportDataAccessWorkspace>;
export default meta; type Story = StoryObj<typeof meta>;
export const RevealAndClear: Story = { play: async ({ canvas, msw }) => { const called = fn(); msw.use(http.post("/api/v1/support/data-access-grants/:grantId/reveals", async ({ request }) => { called(); expect(await request.json()).toEqual({ fields: grant.fields }); msw.use(http.get("/api/v1/support/data-access-grants/:grantId", () => HttpResponse.json({ grant: { ...grant, state: "CONSUMED", reservedReveals: 1 }, viewerRole: "REQUESTER" }))); return HttpResponse.json({ revealAttemptId: grantId, grantId, caseId, subjectId: grant.subjectId, values: { CUSTOMER_PRIMARY_PHONE: "010-1234-5678" }, revealedAt: "2026-09-10T09:04:00Z" }); })); await userEvent.click(canvas.getByRole("heading", { name: "개인정보 열람" })); await waitFor(() => expect(canvas.getByRole("button", { name: "선택한 정보 한시 열람" })).toBeEnabled()); await userEvent.click(canvas.getByRole("button", { name: "선택한 정보 한시 열람" })); await waitFor(() => expect(called).toHaveBeenCalledOnce()); await expect(await canvas.findByText("010-1234-5678")).toBeVisible(); await expect(canvas.getByRole("button", { name: "기존 열람 승인 요청 찾기" })).toBeDisabled(); await userEvent.click(canvas.getByRole("button", { name: "지금 지우기" })); await expect(canvas.queryByText("010-1234-5678")).not.toBeInTheDocument(); await waitFor(() => expect(canvas.queryByRole("button", { name: "선택한 정보 한시 열람" })).not.toBeInTheDocument()); } };
export const LegacyRequest: Story = { parameters: { msw: { handlers: [http.get("/api/v1/support/data-access-grants/:grantId", () => HttpResponse.json({ grant: { ...grant, authorizationBasis: "LEGACY", state: "APPROVAL_PENDING", expiresAt: null }, viewerRole: "APPROVER" }))] } }, play: async ({ canvas }) => { await expect(await canvas.findByText("이전 정책의 요청입니다")).toBeVisible(); await expect(canvas.queryByRole("button", { name: "열람 승인" })).not.toBeInTheDocument(); } };
export const OtherOperatorCannotReveal: Story = { parameters: { msw: { handlers: [http.get("/api/v1/support/data-access-grants/:grantId", () => HttpResponse.json({ grant, viewerRole: "APPROVER" }))] } }, play: async ({ canvas }) => { await canvas.findByText(`열람 요청 ID ${grantId}`); await expect(canvas.queryByRole("button", { name: "선택한 정보 한시 열람" })).not.toBeInTheDocument(); } };
export const OneClickReveal: Story = { args: { initialGrantId: undefined, supportCase }, play: async ({ canvas, msw }) => {
  const revealed = fn();
  msw.use(http.post("/api/v1/support/cases/:caseId/data-access-grants", async ({ request }) => { expect(await request.json()).toEqual({ subjectLinkId: grant.subjectLinkId, purpose: "CASE_RESOLUTION", fields: ["CUSTOMER_PRIMARY_PHONE"], reasonCode: "CASE_HANDLING" }); return HttpResponse.json(grant, { status: 201 }); }),
    http.post("/api/v1/support/data-access-grants/:grantId/reveals", () => { revealed(); return HttpResponse.json({ revealAttemptId: grantId, grantId, caseId, subjectId: grant.subjectId, values: { CUSTOMER_PRIMARY_PHONE: "010-1234-5678" }, revealedAt: "2026-09-10T09:04:00Z" }); }));
  await userEvent.click(canvas.getByRole("checkbox", { name: /고객 등록 전화번호/ }));
  await userEvent.click(canvas.getByRole("button", { name: "정보 보기" }));
  await expect(await canvas.findByText("010-1234-5678")).toBeVisible(); await expect(revealed).toHaveBeenCalledOnce();
  window.dispatchEvent(new Event("blur")); await waitFor(() => expect(canvas.queryByText("010-1234-5678")).not.toBeInTheDocument());
} };
export const MultipleSubjectsRequireSelection: Story = { args: { initialGrantId: undefined, supportCase: { ...supportCase, subjectLinks: [...supportCase.subjectLinks, { ...supportCase.subjectLinks[0]!, linkId: grantId }] } }, play: async ({ canvas }) => { await expect(canvas.getByLabelText("열람 대상")).toHaveValue(""); await expect(canvas.getByRole("button", { name: "정보 보기" })).toBeDisabled(); } };
export const Expired: Story = { parameters: { msw: { handlers: [http.get("/api/v1/support/data-access-grants/:grantId", () => HttpResponse.json({ grant: { ...grant, expiresAt: "2026-09-10T09:03:00Z" }, viewerRole: "REQUESTER" }))] } }, play: async ({ canvas }) => { await expect(await canvas.findByText("열람 기한이 지났습니다")).toBeVisible(); await expect(canvas.queryByRole("button", { name: "선택한 정보 한시 열람" })).not.toBeInTheDocument(); } };
export const UnknownReveal: Story = { play: async ({ canvas, msw }) => { msw.use(http.post("/api/v1/support/data-access-grants/:grantId/reveals", () => HttpResponse.error())); await userEvent.click(canvas.getByRole("heading", { name: "개인정보 열람" })); await waitFor(() => expect(canvas.getByRole("button", { name: "선택한 정보 한시 열람" })).toBeEnabled()); await userEvent.click(canvas.getByRole("button", { name: "선택한 정보 한시 열람" })); await expect(await canvas.findByText("원문 열람 응답을 확인하지 못했습니다")).toBeVisible(); await expect(canvas.queryByRole("button", { name: "선택한 정보 한시 열람" })).not.toBeInTheDocument(); } };
export const FailedRefresh: Story = { play: async ({ canvas, msw }) => { await canvas.findByRole("button", { name: "선택한 정보 한시 열람" }); msw.use(http.get("/api/v1/support/data-access-grants/:grantId", () => HttpResponse.json({ code: "ACCESS_DENIED", correlationId: "GRANT-REVOKED" }, { status: 403 }))); await userEvent.click(canvas.getByRole("button", { name: "열람 요청 상태 새로고침" })); await expect(await canvas.findByText("문의 코드 GRANT-REVOKED")).toBeVisible(); await expect(canvas.queryByRole("button", { name: "선택한 정보 한시 열람" })).not.toBeInTheDocument(); } };

export const SelectExistingRequest: Story = {
  args: { initialGrantId: undefined },
  play: async ({ canvas, msw }) => {
    const inspected = fn();
    msw.use(
      http.get("/api/v1/support/work-items", () => HttpResponse.json({ items: [{ requestId: grantId, caseId: caseId, kind: "DATA_ACCESS", caseCategory: "ACCOUNT_RECOVERY", caseOpenedAt: "2026-09-10T09:00:00Z", purpose: "CASE_RESOLUTION", state: "PENDING", createdAt: "2026-09-10T09:05:00Z", expiresAt: null }], nextCursor: null })),
      http.get("/api/v1/support/data-access-grants/:grantId", async ({ params }) => { expect(params.grantId).toBe(grantId); inspected(); return HttpResponse.json({ grant, viewerRole: "REQUESTER" }); }),
    );
    await userEvent.click(canvas.getByRole("button", { name: "기존 열람 승인 요청 찾기" }));
    await userEvent.click(await canvas.findByRole("button", { name: "이 요청 열기" }));
    await waitFor(() => expect(inspected).toHaveBeenCalled());
    await expect(canvas.getByRole("button", { name: "기존 열람 승인 요청 찾기" })).toHaveAttribute("aria-expanded", "false");
  },
};

export const DirectAccessWithoutVerification: Story = {
  args: { initialGrantId: undefined, supportCase: { caseId: "97000000-0000-4000-8000-000000000001", state: "IN_PROGRESS", subjectLinks: [{ linkId: "97000000-0000-4000-8000-000000000002", subjectId: "97000000-0000-4000-8000-000000000003", subjectType: "CUSTOMER", display: { state: "AVAILABLE", label: "김*객" } }] } },
  play: async ({ canvas }) => { await expect(await canvas.findByLabelText("열람 대상")).toBeVisible(); await expect(canvas.queryByText("본인확인 시작")).not.toBeInTheDocument(); await expect(canvas.getByRole("button", { name: "정보 보기" })).toBeDisabled(); },
};
