import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { operationsApi } from "../../api/consoleClient";
import { SupportWorkspacePage } from "./SupportWorkspacePage";

const caseId = "a1000000-0000-4000-8000-000000000001";
const customerId = "a2000000-0000-4000-8000-000000000001";
const linkId = "a3000000-0000-4000-8000-000000000001";
const sessionId = "a4000000-0000-4000-8000-000000000001";
const challengeId = "a5000000-0000-4000-8000-000000000001";
const grantId = "a6000000-0000-4000-8000-000000000001";

function response(data: unknown, status = 200) {
  return { data, response: new Response(null, { status }) } as never;
}

const supportCase = {
  caseId,
  state: "IN_PROGRESS",
  priority: "NORMAL",
  assigneeId: "a7000000-0000-4000-8000-000000000001",
  version: 2,
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

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("SupportWorkspacePage", () => {
  it("sends exact PII only in a POST body and connects the masked candidate to a Case", async () => {
    const post = vi.spyOn(operationsApi, "POST").mockImplementation((async (path: string) => {
      if (path === "/support/searches") return response({
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
      });
      if (path === "/support/cases") return response({ ...supportCase, subjectLinks: [] }, 201);
      if (path.endsWith("/subject-links")) return response(supportCase.subjectLinks[0]);
      throw new Error(`unexpected POST ${path}`);
    }) as never);
    vi.spyOn(operationsApi, "GET").mockImplementation((async (path: string) => {
      if (path === "/support/cases/{caseId}") return response(supportCase);
      if (path.endsWith("/timeline")) return response({ items: [], nextCursor: null });
      throw new Error(`unexpected GET ${path}`);
    }) as never);

    render(<MemoryRouter><SupportWorkspacePage /></MemoryRouter>);
    await userEvent.type(screen.getByLabelText("전화번호 또는 이메일"), "010-0000-0000");
    await userEvent.click(screen.getByRole("button", { name: "정확 검색" }));

    expect(await screen.findByText("홍*동")).toBeVisible();
    const searchCall = post.mock.calls[0] as unknown as [string, { body: Record<string, unknown> }];
    expect(searchCall[0]).toBe("/support/searches");
    expect(searchCall[1].body).toEqual({
      criterion: { type: "PHONE", value: "010-0000-0000" },
      subjectTypes: ["CUSTOMER"],
      reasonCode: "CASE_INTAKE",
    });
    expect(JSON.stringify(searchCall[1])).not.toContain("query");

    await userEvent.click(screen.getByRole("button", { name: "새 Case에 연결" }));
    expect(await screen.findByText(`CASE ${caseId}`)).toBeVisible();
    const postCalls = post.mock.calls as unknown as Array<[string, unknown]>;
    expect(postCalls.some(([path]) => path.endsWith("/subject-links"))).toBe(true);
  });

  it("keeps proof and revealed PII route-local while wiring verification, grant, reveal and timeline", async () => {
    vi.spyOn(operationsApi, "GET").mockImplementation((async (path: string) => {
      if (path === "/support/cases/{caseId}") return response(supportCase);
      if (path.endsWith("/timeline")) return response({
        items: [{
          itemId: "a8000000-0000-4000-8000-000000000001",
          source: "ORDERING",
          type: "ORDER_STATE",
          state: "COMPLETED",
          summary: "주문 픽업 완료",
          amountKrw: 7500,
          occurredAt: "2026-08-23T09:30:00Z",
        }],
        nextCursor: null,
      });
      throw new Error(`unexpected GET ${path}`);
    }) as never);
    vi.spyOn(operationsApi, "POST").mockImplementation((async (path: string) => {
      if (path.endsWith("/verification-sessions")) return response({
        sessionId,
        caseId,
        subjectLinkId: linkId,
        subjectType: "CUSTOMER",
        subjectId: customerId,
        purpose: "CONTACT_CONFIRMATION",
        actionScope: "PERSONAL_DATA_REVEAL",
        requestedLevel: "ENHANCED",
        achievedLevel: "UNVERIFIED",
        state: "PENDING",
        invalidAttempts: 0,
        startedAt: "2026-08-23T09:00:00Z",
        expiresAt: "2026-08-23T09:15:00Z",
        version: 1,
        challenges: [],
      }, 201);
      if (path.endsWith("/challenges")) return response({
        challengeId,
        sessionId,
        channel: "REGISTERED_PHONE",
        state: "ISSUED",
        requestedAt: "2026-08-23T09:01:00Z",
        expiresAt: "2026-08-23T09:06:00Z",
      }, 201);
      if (path.endsWith("/verifications")) return response({
        challenge: {
          challengeId,
          sessionId,
          channel: "REGISTERED_PHONE",
          state: "VERIFIED",
          requestedAt: "2026-08-23T09:01:00Z",
          expiresAt: "2026-08-23T09:06:00Z",
        },
        sessionState: "VERIFIED",
        achievedLevel: "ENHANCED",
        invalidAttempts: 0,
        lockedUntil: null,
      });
      if (path.endsWith("/data-access-grants")) return response({
        grantId,
        caseId,
        subjectLinkId: linkId,
        subjectType: "CUSTOMER",
        subjectId: customerId,
        purpose: "CONTACT_CONFIRMATION",
        fields: ["CUSTOMER_PRIMARY_PHONE"],
        risk: "SENSITIVE",
        state: "ACTIVE",
        maxReveals: 1,
        reservedReveals: 0,
        requestedAt: "2026-08-23T09:03:00Z",
        expiresAt: "2026-08-23T09:08:00Z",
        version: 1,
      }, 201);
      if (path.endsWith("/reveals")) return response({
        revealAttemptId: "a9000000-0000-4000-8000-000000000001",
        grantId,
        caseId,
        subjectId: customerId,
        values: { CUSTOMER_PRIMARY_PHONE: "010-1234-5678" },
        revealedAt: "2026-08-23T09:04:00Z",
      });
      throw new Error(`unexpected POST ${path}`);
    }) as never);

    render(<MemoryRouter><SupportWorkspacePage /></MemoryRouter>);
    await userEvent.type(screen.getByLabelText("기존 Case ID"), caseId);
    await userEvent.click(screen.getByRole("button", { name: "Case 열기" }));
    expect(await screen.findByText("주문 픽업 완료")).toBeVisible();

    await userEvent.click(screen.getByRole("button", { name: "ENHANCED 본인확인 시작" }));
    await userEvent.click(screen.getByRole("button", { name: "등록 전화로 challenge 발급" }));
    await userEvent.type(screen.getByLabelText("일회성 proof"), "123456");
    await userEvent.click(screen.getByRole("button", { name: "proof 검증" }));
    await waitFor(() => expect(screen.queryByLabelText("일회성 proof")).not.toBeInTheDocument());

    await userEvent.click(screen.getByRole("button", { name: "전화번호 Grant 요청" }));
    await userEvent.click(screen.getByRole("button", { name: "승인된 전화번호 열람" }));
    expect(await screen.findByText("010-1234-5678")).toBeVisible();
    expect(JSON.stringify(localStorage) + JSON.stringify(sessionStorage)).not.toContain("010-1234-5678");
    await userEvent.click(screen.getByRole("button", { name: "원문 즉시 지우기" }));
    expect(screen.queryByText("010-1234-5678")).not.toBeInTheDocument();
  });
});
