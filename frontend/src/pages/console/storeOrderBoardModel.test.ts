import { describe, expect, it } from "vitest";
import type { StoreOrderBoard, StoreOrderBoardItem } from "./storeOrderBoardModel";
import { reconcileBoardItem, sortStoreOrderBoard } from "./storeOrderBoardModel";

function item(overrides: Partial<StoreOrderBoardItem> = {}): StoreOrderBoardItem {
  return {
    orderReference: "ORD-002",
    pickupNumber: "A-002",
    pickupBusinessDate: "2026-08-20",
    lane: "PENDING_ACCEPTANCE",
    status: "PAID",
    pickupWindowStart: "2026-08-20T03:20:00Z",
    pickupWindowEnd: "2026-08-20T03:30:00Z",
    itemSummary: "아메리카노 1잔",
    acceptanceDeadlineAt: "2026-08-20T03:05:00Z",
    acceptancePhase: "OPEN",
    allowedActions: ["ACCEPT", "REJECT"],
    ...overrides,
  };
}

function board(items: StoreOrderBoardItem[]): StoreOrderBoard {
  return {
    groups: [
      { pickupBusinessDate: "2026-08-20", items },
      { pickupBusinessDate: "2026-08-19", items: [item({ orderReference: "ORD-OLD", pickupBusinessDate: "2026-08-19" })] },
    ],
    overflow: [{ lane: "PENDING_ACCEPTANCE", overflowCount: 2, nextCursor: "next" }],
  };
}

describe("store order board model", () => {
  it("sorts business dates, pickup windows, and reference ties without mutating the source", () => {
    const late = item({ orderReference: "ORD-LATE", pickupWindowStart: "2026-08-20T03:30:00Z" });
    const tieB = item({ orderReference: "ORD-B" });
    const tieA = item({ orderReference: "ORD-A" });
    const source = board([late, tieB, tieA]);

    const sorted = sortStoreOrderBoard(source.groups, source.overflow);

    expect(sorted.groups.map((group) => group.pickupBusinessDate)).toEqual(["2026-08-19", "2026-08-20"]);
    expect(sorted.groups.at(1)?.items.map((entry) => entry.orderReference)).toEqual(["ORD-A", "ORD-B", "ORD-LATE"]);
    expect(source.groups.at(0)?.items).toEqual([late, tieB, tieA]);
    expect(sorted.overflow).toBe(source.overflow);
  });

  it("moves a changed item to its new business date and lane exactly once", () => {
    const source = board([item()]);
    const changed = item({
      pickupBusinessDate: "2026-08-21",
      lane: "PREPARING",
      status: "PREPARING",
      allowedActions: ["MARK_READY"],
    });

    const reconciled = reconcileBoardItem(source, changed);

    expect(reconciled.groups.flatMap((group) => group.items).filter((entry) => entry.orderReference === changed.orderReference)).toEqual([changed]);
    expect(reconciled.groups.map((group) => group.pickupBusinessDate)).toEqual(["2026-08-19", "2026-08-21"]);
  });

  it("removes a terminal item and drops its empty date group", () => {
    const source = board([item()]);

    const reconciled = reconcileBoardItem(source, item({ lane: undefined, status: "COMPLETED", allowedActions: [] }));

    expect(reconciled.groups.flatMap((group) => group.items).map((entry) => entry.orderReference)).toEqual(["ORD-OLD"]);
    expect(reconciled.groups.map((group) => group.pickupBusinessDate)).toEqual(["2026-08-19"]);
  });
});
