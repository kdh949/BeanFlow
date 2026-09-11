import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { operationsApi } from "../../api/consoleClient";
import { SupportWorkspacePage } from "./SupportWorkspacePage";

const caseId = "a1000000-0000-4000-8000-000000000001";
const customerId = "a2000000-0000-4000-8000-000000000001";
const linkId = "a3000000-0000-4000-8000-000000000001";

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
  it("does not show an empty active case when a linked timeline fails", async () => {
    vi.spyOn(operationsApi, "GET").mockImplementation((async (path: string) => {
      if (path.endsWith("/timeline")) throw new Error("timeline unavailable");
      return response(supportCase);
    }) as never);
    render(<MemoryRouter initialEntries={[`/support?caseId=${caseId}`]}><SupportWorkspacePage /></MemoryRouter>);
    expect(await screen.findByRole("alert")).toBeVisible();
    expect(screen.queryByText(`상담 ID ${caseId}`)).not.toBeInTheDocument();
    expect(screen.queryByText("표시할 이력이 없습니다")).not.toBeInTheDocument();
  });

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

    await userEvent.click(screen.getByRole("button", { name: "새 상담 건에 연결" }));
    expect(await screen.findByRole("link", { name: "상담 상태·담당자 관리" })).toHaveAttribute("href", `/support/cases/${caseId}`);
    const postCalls = post.mock.calls as unknown as Array<[string, unknown]>;
    expect(postCalls.some(([path]) => path.endsWith("/subject-links"))).toBe(true);
  });

});
