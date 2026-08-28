import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";
import { HttpResponse, http } from "msw";
import { SupportWorkspacePage } from "./SupportWorkspacePage";

const caseId = "a1000000-0000-4000-8000-000000000001";
const customerId = "a2000000-0000-4000-8000-000000000001";
const linkId = "a3000000-0000-4000-8000-000000000001";
const sessionId = "a4000000-0000-4000-8000-000000000001";
const challengeId = "a5000000-0000-4000-8000-000000000001";
const grantId = "a6000000-0000-4000-8000-000000000001";

const activeCase = {
  caseId,
  state: "IN_PROGRESS",
  priority: "HIGH",
  assigneeId: "a7000000-0000-4000-8000-000000000001",
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
    summary: "주문 픽업 완료",
    amountKrw: 7500,
    occurredAt: "2026-08-23T09:30:00Z",
  }, {
    itemId: "a8000000-0000-4000-8000-000000000002",
    source: "PAYMENT",
    type: "REFUND_STATE",
    state: "RECONCILING",
    summary: "환불 결과 재확인 중",
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
    docs: {
      description: {
        component:
          "exact PII를 POST body로만 검색하고 masked candidate를 Case에 연결한 뒤 Verification, purpose-bound Grant/reveal, timeline과 보상 판단을 처리합니다. 원문은 route-local 메모리에서만 60초 유지합니다.",
      },
      story: { inline: false, height: "1200px" },
    },
    routing: { path: "/support", initialEntry: "/support" },
  },
} satisfies Meta<typeof SupportWorkspacePage>;

export default meta;
type Story = StoryObj<typeof meta>;

async function openCase(canvas: Parameters<NonNullable<Story["play"]>>[0]["canvas"]) {
  await userEvent.type(canvas.getByLabelText("기존 상담 건 ID"), caseId);
  await userEvent.click(canvas.getByRole("button", { name: "상담 건 열기" }));
  await expect(await canvas.findByText(`상담 ${caseId}`)).toBeVisible();
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
  parameters: { msw: { handlers: caseHandlers } },
  play: async ({ canvas }) => {
    await openCase(canvas);
    await expect(canvas.getByText("주문 픽업 완료")).toBeVisible();
    await expect(canvas.getByText("환불 결과 재확인 중")).toBeVisible();
  },
};

export const VerifiedGrantReveal: Story = {
  parameters: {
    msw: { handlers: [
      ...caseHandlers,
      http.post("/api/v1/support/cases/:caseId/verification-sessions", () => HttpResponse.json({
        sessionId, caseId, subjectLinkId: linkId, subjectType: "CUSTOMER", subjectId: customerId,
        purpose: "CONTACT_CONFIRMATION", actionScope: "PERSONAL_DATA_REVEAL", requestedLevel: "ENHANCED",
        achievedLevel: "UNVERIFIED", state: "PENDING", invalidAttempts: 0,
        startedAt: "2026-08-23T09:00:00Z", expiresAt: "2026-08-23T09:15:00Z", version: 1, challenges: [],
      }, { status: 201 })),
      http.post("/api/v1/support/verification-sessions/:sessionId/challenges", () => HttpResponse.json({
        challengeId, sessionId, channel: "REGISTERED_PHONE", state: "ISSUED",
        requestedAt: "2026-08-23T09:01:00Z", expiresAt: "2026-08-23T09:06:00Z",
      }, { status: 201 })),
      http.post("/api/v1/support/verification-challenges/:challengeId/verifications", () => HttpResponse.json({
        challenge: { challengeId, sessionId, channel: "REGISTERED_PHONE", state: "VERIFIED", requestedAt: "2026-08-23T09:01:00Z", expiresAt: "2026-08-23T09:06:00Z" },
        sessionState: "VERIFIED", achievedLevel: "ENHANCED", invalidAttempts: 0, lockedUntil: null,
      })),
      http.post("/api/v1/support/cases/:caseId/data-access-grants", () => HttpResponse.json({
        grantId, caseId, subjectLinkId: linkId, subjectType: "CUSTOMER", subjectId: customerId,
        purpose: "CONTACT_CONFIRMATION", fields: ["CUSTOMER_PRIMARY_PHONE"], risk: "SENSITIVE", state: "ACTIVE",
        maxReveals: 1, reservedReveals: 0, requestedAt: "2026-08-23T09:03:00Z", expiresAt: "2026-08-23T09:08:00Z", version: 1,
      }, { status: 201 })),
      http.post("/api/v1/support/data-access-grants/:grantId/reveals", () => HttpResponse.json({
        revealAttemptId: "a9000000-0000-4000-8000-000000000001", grantId, caseId, subjectId: customerId,
        values: { CUSTOMER_PRIMARY_PHONE: "010-1234-5678" }, revealedAt: "2026-08-23T09:04:00Z",
      })),
    ] },
  },
  play: async ({ canvas }) => {
    await openCase(canvas);
    await userEvent.click(canvas.getByRole("button", { name: "강화 본인확인 시작" }));
    await userEvent.click(await canvas.findByRole("button", { name: "등록 전화로 인증 코드 발급" }));
    await userEvent.type(await canvas.findByLabelText("일회성 인증 코드"), "123456");
    await userEvent.click(canvas.getByRole("button", { name: "인증 코드 확인" }));
    await userEvent.click(await canvas.findByRole("button", { name: "전화번호 열람 권한 요청" }));
    await userEvent.click(await canvas.findByRole("button", { name: "승인된 전화번호 열람" }));
    await expect(await canvas.findByText("010-1234-5678")).toBeVisible();
    await expect(canvas.getByRole("button", { name: "지금 지우기" })).toBeVisible();
  },
};

export const VerificationLocked: Story = {
  parameters: {
    msw: { handlers: [
      ...caseHandlers,
      http.post("/api/v1/support/cases/:caseId/verification-sessions", () => HttpResponse.json({
        sessionId, caseId, subjectLinkId: linkId, subjectType: "CUSTOMER", subjectId: customerId,
        purpose: "CONTACT_CONFIRMATION", actionScope: "PERSONAL_DATA_REVEAL", requestedLevel: "ENHANCED",
        achievedLevel: "UNVERIFIED", state: "LOCKED", invalidAttempts: 5,
        startedAt: "2026-08-23T09:00:00Z", expiresAt: "2026-08-23T09:15:00Z", version: 6, challenges: [],
      }, { status: 201 })),
    ] },
  },
  play: async ({ canvas }) => {
    await openCase(canvas);
    await userEvent.click(canvas.getByRole("button", { name: "강화 본인확인 시작" }));
    await expect(await canvas.findByText("LOCKED")).toBeVisible();
    await expect(canvas.queryByRole("button", { name: "등록 전화로 인증 코드 발급" })).not.toBeInTheDocument();
  },
};

export const TerminalCase: Story = {
  parameters: {
    msw: { handlers: [
      http.get("/api/v1/support/cases/:caseId", () => HttpResponse.json({ ...activeCase, state: "CLOSED", closedAt: "2026-08-23T10:00:00Z" })),
      http.get("/api/v1/support/cases/:caseId/timeline", () => HttpResponse.json(timeline)),
    ] },
  },
  play: async ({ canvas }) => {
    await openCase(canvas);
    await expect(canvas.getByText(/종료된 상담 건에서는/)).toBeVisible();
    await expect(canvas.queryByRole("button", { name: "강화 본인확인 시작" })).not.toBeInTheDocument();
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
