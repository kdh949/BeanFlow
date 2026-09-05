import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { merchantApi } from "../../api/merchantClient";
import { StoreDisputesPage } from "./StoreDisputesPage";

const storeId = "10000000-0000-4000-8000-000000000001";

function dispute(sequence: number) {
  return {
    disputeId: `dispute-${sequence}`,
    settlementItemId: `item-${sequence}`,
    state: "FILED" as const,
    expectedAdjustmentKrw: 1_000 * sequence,
    heldAmountKrw: 1_000 * sequence,
    filedAt: "2026-08-16T10:00:00+09:00",
  };
}

type Query = { cursor?: string } | undefined;

function renderPage() {
  return render(<MemoryRouter><StoreDisputesPage /></MemoryRouter>);
}

beforeEach(() => {
  sessionStorage.clear();
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("store disputes pagination", () => {
  it("loads a second page on demand without losing the first page's items", async () => {
    vi.spyOn(merchantApi, "GET").mockImplementation((async (
      path: string,
      options?: { params?: { query?: Query } },
    ) => {
      if (path === "/merchant/me/stores") {
        return {
          data: [{ storeId, storeName: "시청점", membershipRole: "OWNER" }],
          response: new Response(null, { status: 200 }),
        };
      }
      if (path === "/stores/{storeId}/disputes") {
        const cursor = options?.params?.query?.cursor;
        if (!cursor) {
          return { data: { items: [dispute(1)], page: { nextCursor: "page-2" } }, response: new Response(null, { status: 200 }) };
        }
        if (cursor === "page-2") {
          return { data: { items: [dispute(2)], page: {} }, response: new Response(null, { status: 200 }) };
        }
      }
      throw new Error(`unexpected GET ${path}`);
    }) as never);

    renderPage();

    expect(await screen.findAllByText("₩1,000")).toHaveLength(2);
    expect(screen.queryByText("₩2,000")).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: /이의제기 더 보기/ }));

    expect(await screen.findAllByText("₩2,000")).toHaveLength(2);
    expect(screen.getAllByText("₩1,000")).toHaveLength(2);
    expect(screen.queryByRole("button", { name: /이의제기 더 보기/ })).not.toBeInTheDocument();
  });

  it("resets the accumulated pages when the state filter changes", async () => {
    const seenCursors: Array<string | undefined> = [];
    vi.spyOn(merchantApi, "GET").mockImplementation((async (
      path: string,
      options?: { params?: { path?: { storeId: string }; query?: Query & { state?: string } } },
    ) => {
      if (path === "/merchant/me/stores") {
        return {
          data: [{ storeId, storeName: "시청점", membershipRole: "OWNER" }],
          response: new Response(null, { status: 200 }),
        };
      }
      if (path === "/stores/{storeId}/disputes") {
        const query = options?.params?.query;
        seenCursors.push(query?.cursor);
        if (query?.state === "ACCEPTED") {
          return { data: { items: [], page: {} }, response: new Response(null, { status: 200 }) };
        }
        return { data: { items: [dispute(1)], page: { nextCursor: "page-2" } }, response: new Response(null, { status: 200 }) };
      }
      throw new Error(`unexpected GET ${path}`);
    }) as never);

    renderPage();
    await screen.findAllByText("₩1,000");
    await userEvent.click(screen.getByRole("button", { name: /이의제기 더 보기/ }));
    await waitFor(() => expect(seenCursors).toContain("page-2"));

    await userEvent.click(screen.getByRole("button", { name: "인정" }));

    expect(await screen.findByText("접수한 이의제기가 없습니다")).toBeInTheDocument();
    expect(screen.queryAllByText("₩1,000")).toHaveLength(0);
    // The filter change must start a fresh first page, not continue from the
    // cursor the previous filter's "더 보기" had reached.
    expect(seenCursors.at(-1)).toBeUndefined();
  });
});
