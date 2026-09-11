import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";
import { HttpResponse, http } from "msw";
import { SupportWorkspacePage } from "./SupportWorkspacePage";

const caseId = "a1000000-0000-4000-8000-000000000001";
const customerId = "a2000000-0000-4000-8000-000000000001";
const linkId = "a3000000-0000-4000-8000-000000000001";

const activeCase = {
  caseId,
  category: "ACCOUNT_RECOVERY",
  state: "IN_PROGRESS",
  priority: "HIGH",
  assigneeId: "a7000000-0000-4000-8000-000000000001",
  assigneeDisplay: { state: "AVAILABLE", loginName: "support.current", observedAt: "2026-09-11T00:00:00Z" },
  version: 4,
  openedAt: "2026-08-23T09:00:00Z",
  subjectLinks: [{
    linkId,
    subjectType: "CUSTOMER",
    subjectId: customerId,
    relationship: "REQUESTER",
    linkedAt: "2026-08-23T09:01:00Z",
    caseVersion: 2,
  }],
};

const timeline = {
  items: [{
    itemId: "a8000000-0000-4000-8000-000000000001",
    source: "ORDERING",
    type: "ORDER_STATE",
    state: "COMPLETED",
    summary: "ORDER_STATE:COMPLETED",
    amountKrw: 7500,
    occurredAt: "2026-08-23T09:30:00Z",
  }, {
    itemId: "a8000000-0000-4000-8000-000000000002",
    source: "PAYMENT",
    type: "REFUND_STATE",
    state: "RECONCILING",
    summary: "REFUND_STATE:RECONCILING",
    amountKrw: 7500,
    occurredAt: "2026-08-23T09:35:00Z",
  }],
  nextCursor: null,
};

const caseHandlers = [
  http.get("/api/v1/support/cases/:caseId", () => HttpResponse.json(activeCase)),
  http.get("/api/v1/support/cases/:caseId/timeline", () => HttpResponse.json(timeline)),
];

const meta = {
  title: "Pages/Support/Workspace",
  component: SupportWorkspacePage,
  tags: ["autodocs"],
  parameters: {
    a11y: { test: "error" }, layout: "fullscreen",
    docs: {
      description: {
        component:
          "exact PII를 POST body로만 검색하고 masked candidate를 Case에 연결한 뒤 Verification, purpose-bound Grant/reveal, timeline과 보상 판단을 처리합니다. 원문은 route-local 메모리에서만 60초 유지합니다.",
      },
      story: { inline: false, height: "1200px" },
    },
    routing: { surface: "support", path: "/support", initialEntry: "/support" },
  },
} satisfies Meta<typeof SupportWorkspacePage>;

export default meta;
type Story = StoryObj<typeof meta>;

async function openCase(canvas: Parameters<NonNullable<Story["play"]>>[0]["canvas"]) {
  await expect(await canvas.findByText("계정 복구 상담")).toBeVisible();
}

export const MaskedExactSearch: Story = {
  parameters: {
    msw: { handlers: [http.post("/api/v1/support/searches", () => HttpResponse.json({
      searchId: "a0000000-0000-4000-8000-000000000001",
      items: [{
        subjectType: "CUSTOMER",
        subjectId: customerId,
        maskedDisplayName: "홍*동",
        matchedCriterionType: "PHONE",
        maskedMatchedValue: "***-****-0000",
      }],
      matchedCount: 1,
      ambiguous: false,
      hasMore: false,
    }))] },
  },
  play: async ({ canvas }) => {
    await userEvent.type(canvas.getByLabelText("전화번호 또는 이메일"), "010-0000-0000");
    await userEvent.click(canvas.getByRole("button", { name: "정확 검색" }));
    await expect(await canvas.findByText("홍*동")).toBeVisible();
    await expect(canvas.getByText("***-****-0000")).toBeVisible();
    await expect(canvas.queryByText("010-0000-0000")).not.toBeInTheDocument();
  },
};

export const ActiveCaseTimeline: Story = {
  parameters: { routing: { surface: "support", path: "/support", initialEntry: `/support?caseId=${caseId}` }, msw: { handlers: caseHandlers } },
  play: async ({ canvas }) => {
    await openCase(canvas);
    await expect(canvas.getByText("주문 픽업 완료")).toBeVisible();
    await expect(canvas.getByText("환불 결과 재확인 중")).toBeVisible();
  },
};

export const VerificationEntry: Story = { parameters: { routing: { surface: "support", path: "/support", initialEntry: `/support?caseId=${caseId}` }, msw: { handlers: caseHandlers } }, play: async ({ canvas }) => { await openCase(canvas); await expect(canvas.getByLabelText("본인확인 대상")).toBeVisible(); await expect(canvas.getByLabelText("인증 사용 업무")).toBeVisible(); await expect(canvas.getByRole("button", { name: "기존 열람 승인 요청 찾기" })).toBeVisible(); } };

export const TerminalCase: Story = {
  parameters: { routing: { surface: "support", path: "/support", initialEntry: `/support?caseId=${caseId}` },
    msw: { handlers: [
      http.get("/api/v1/support/cases/:caseId", () => HttpResponse.json({ ...activeCase, state: "CLOSED", closedAt: "2026-08-23T10:00:00Z" })),
      http.get("/api/v1/support/cases/:caseId/timeline", () => HttpResponse.json(timeline)),
    ] },
  },
  play: async ({ canvas }) => {
    await openCase(canvas);
    await expect(canvas.getByText(/종료된 상담 건에서는/)).toBeVisible();
    await expect(canvas.queryByRole("button", { name: "본인확인 시작" })).not.toBeInTheDocument();
  },
};

export const SearchRateLimited: Story = {
  parameters: {
    msw: { handlers: [http.post("/api/v1/support/searches", () => HttpResponse.json({
      code: "SUPPORT_SEARCH_RATE_LIMITED",
      message: "5분 검색 한도를 초과했습니다. 서버가 안내한 시간 뒤 다시 시도해 주세요.",
      correlationId: "REQ-SUPPORT-429",
    }, { status: 429, headers: { "Retry-After": "120" } }))] },
  },
  play: async ({ canvas }) => {
    await userEvent.type(canvas.getByLabelText("전화번호 또는 이메일"), "010-0000-0000");
    await userEvent.click(canvas.getByRole("button", { name: "정확 검색" }));
    await expect(await canvas.findByText("검색 요청이 너무 많습니다")).toBeVisible();
  },
};

export const OpenCaseFromLink: Story = {
  parameters: { routing: { path: "/support", initialEntry: `/support?caseId=${caseId}`, surface: "support" }, msw: { handlers: caseHandlers } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("계정 복구 상담")).toBeVisible();
    await expect(canvas.getByText("주문 픽업 완료")).toBeVisible();
    await expect(canvas.getByRole("link", { name: "상담 후속 업무" })).toHaveAttribute("href", `/support/follow-up?caseId=${caseId}`);
  },
};

export const CaseManagementLink: Story = {
  parameters: { routing: { surface: "support", path: "/support", initialEntry: `/support?caseId=${caseId}` }, msw: { handlers: caseHandlers } },
  play: async ({ canvas }) => { await openCase(canvas); await expect(canvas.getByRole("link", { name: "상담 상태·담당자 관리" })).toHaveAttribute("href", `/support/cases/${caseId}`); },
};

export const PartiallyCreatedCase: Story = {
  parameters: { msw: { handlers: [...caseHandlers, http.post("/api/v1/support/searches", () => HttpResponse.json({ searchId: "a0000000-0000-4000-8000-000000000001", items: [{ subjectType: "CUSTOMER", subjectId: customerId, maskedDisplayName: "홍*동", matchedCriterionType: "PHONE", maskedMatchedValue: "***-****-0000" }], matchedCount: 1, ambiguous: false, hasMore: false })), http.post("/api/v1/support/cases", () => HttpResponse.json({ ...activeCase, subjectLinks: [] }, { status: 201 })), http.post("/api/v1/support/cases/:caseId/subject-links", () => HttpResponse.json({ code: "DEPENDENCY_UNAVAILABLE", correlationId: "CASE-LINK-FAILED" }, { status: 503 }))] } },
  play: async ({ canvas }) => { await userEvent.type(canvas.getByLabelText("전화번호 또는 이메일"), "01000000000"); await userEvent.click(canvas.getByRole("button", { name: "정확 검색" })); await canvas.findByRole("button", { name: "새 상담 건에 연결" }); await userEvent.selectOptions(canvas.getByLabelText("문의 분류"), "SAFETY"); await userEvent.selectOptions(canvas.getByLabelText("우선순위"), "LOW"); await userEvent.click(await canvas.findByRole("button", { name: "새 상담 건에 연결" })); await expect(await canvas.findByText("계정 복구 상담")).toBeVisible(); await expect(canvas.getByRole("link", { name: "상담 상태·담당자 관리" })).toHaveAttribute("href", `/support/cases/${caseId}`); await expect(await canvas.findByRole("alert")).toBeVisible(); },
};

export const FindExistingCase: Story = { play: async ({ canvas }) => {
  await expect(canvas.getByRole("link", { name: "상담 목록에서 선택" })).toHaveAttribute("href", "/support/cases");
  await expect(canvas.queryByLabelText("기존 상담 건 ID")).not.toBeInTheDocument();
} };

export const PendingDataAccessKeepsCase: Story = {
  parameters: { routing: { surface: "support", path: "/support", initialEntry: `/support?caseId=${caseId}` }, msw: { handlers: caseHandlers } },
  play: async ({ canvas, msw }) => {
    const grantId = "a6000000-0000-4000-8000-000000000001";
    msw.use(
      http.get("/api/v1/support/work-items", () => HttpResponse.json({ items: [{ requestId: grantId, caseId, kind: "DATA_ACCESS", caseCategory: "ACCOUNT_RECOVERY", caseOpenedAt: activeCase.openedAt, purpose: "CONTACT_CONFIRMATION", state: "APPROVAL_PENDING", createdAt: "2026-08-23T09:05:00Z", expiresAt: null }], nextCursor: null })),
      http.get("/api/v1/support/data-access-grants/:grantId", () => HttpResponse.json({ grant: { grantId, caseId, subjectLinkId: linkId, subjectType: "CUSTOMER", subjectId: customerId, purpose: "CONTACT_CONFIRMATION", fields: ["CUSTOMER_PRIMARY_PHONE"], risk: "SENSITIVE", state: "APPROVAL_PENDING", maxReveals: 1, reservedReveals: 0, requestedAt: "2026-08-23T09:05:00Z", expiresAt: null, version: 1 }, viewerRole: "APPROVER" })),
      http.post("/api/v1/support/data-access-grants/:grantId/approvals", () => HttpResponse.error()),
    );
    await openCase(canvas);
    await userEvent.click(canvas.getByRole("button", { name: "기존 열람 승인 요청 찾기" }));
    await userEvent.click(await canvas.findByRole("button", { name: "이 요청 열기" }));
    await userEvent.click(await canvas.findByRole("button", { name: "열람 승인" }));
    await canvas.findByRole("button", { name: "같은 열람 판정 확인" });
    await expect(canvas.getByLabelText("본인확인 대상")).toBeDisabled();
    await expect(canvas.getByLabelText("전화번호 또는 이메일")).toBeDisabled();
    await expect(canvas.getByRole("button", { name: "기존 열람 승인 요청 찾기" })).toBeDisabled();
  },
};

export const LostCaseIntakeKeepsCandidate: Story = {
  parameters: MaskedExactSearch.parameters,
  play: async ({ canvas, msw }) => {
    const attempts: { key: string | null; body: unknown }[] = [];
    msw.use(...caseHandlers,
      http.post("/api/v1/support/cases", async ({ request }) => {
        attempts.push({ key: request.headers.get("Idempotency-Key"), body: await request.json() });
        if (attempts.length === 1) return HttpResponse.error();
        expect(attempts.at(-1)).toEqual(attempts[0]);
        return HttpResponse.json(activeCase, { status: 201 });
      }),
      http.post("/api/v1/support/cases/:caseId/subject-links", () => HttpResponse.json(activeCase.subjectLinks[0], { status: 201 })),
    );
    await userEvent.type(canvas.getByLabelText("전화번호 또는 이메일"), "01000000000");
    await userEvent.click(canvas.getByRole("button", { name: "정확 검색" }));
    await userEvent.click(await canvas.findByRole("button", { name: "새 상담 건에 연결" }));
    await canvas.findByRole("button", { name: "같은 상담 접수 결과 확인" });
    await expect(canvas.getByLabelText("문의 분류")).toBeDisabled();
    await expect(canvas.getByLabelText("전화번호 또는 이메일")).toBeDisabled();
    await userEvent.click(canvas.getByRole("button", { name: "같은 상담 접수 결과 확인" }));
    await expect(await canvas.findByText("계정 복구 상담")).toBeVisible();
  },
};
