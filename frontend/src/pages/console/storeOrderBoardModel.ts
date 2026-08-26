import type { components } from "../../api/schema";

export type StoreOrderBoard = components["schemas"]["StoreOrderBoard"];
export type StoreOrderBoardItem = components["schemas"]["StoreOrderBoardItem"];
export type StoreOrderBoardOverflow = components["schemas"]["StoreOrderBoardOverflow"];
export type StoreOrderBoardOverflowPage = components["schemas"]["StoreOrderBoardOverflowPage"];
export type StoreOrderAction = components["schemas"]["StoreOrderAction"];
export type StoreOrderBoardLane = StoreOrderBoardOverflow["lane"];

export const storeOrderBoardColumns = [
  { key: "acceptance", title: "접수 대기", description: "결제 완료 후 매장 확인 대기", lanes: ["PENDING_ACCEPTANCE"] },
  { key: "preparing", title: "제조 중", description: "접수 완료 및 제조 진행", lanes: ["ACCEPTED", "PREPARING"] },
  { key: "ready", title: "준비 완료", description: "고객 픽업 대기", lanes: ["READY"] },
] as const;

export const storeOrderActionLabels: Record<StoreOrderAction, string> = {
  ACCEPT: "주문 접수",
  REJECT: "주문 거절",
  START_PREPARING: "제조 시작",
  MARK_READY: "준비 완료",
  COMPLETE: "픽업 완료",
};

export const storeOrderBoardLaneLabels: Record<StoreOrderBoardLane, string> = {
  PENDING_ACCEPTANCE: "접수 대기",
  ACCEPTED: "접수 완료",
  PREPARING: "제조 중",
  READY: "준비 완료",
};

const lifecycleByStatus = {
  PAID: ["paidAt", "결제 후"],
  ACCEPTED: ["acceptedAt", "접수 후"],
  PREPARING: ["preparingAt", "제조 시작 후"],
  READY: ["readyAt", "준비 완료 후"],
} as const;

export function storeOrderElapsedLabel(item: StoreOrderBoardItem, now: Date): string | null {
  const milestone = lifecycleByStatus[item.status as keyof typeof lifecycleByStatus];
  if (!milestone || !item.lifecycle) return null;
  const [field, prefix] = milestone;
  const startedAt = item.lifecycle[field];
  if (!startedAt) return null;
  const elapsedMinutes = Math.max(0, Math.floor((now.getTime() - new Date(startedAt).getTime()) / 60_000));
  return `${prefix} ${elapsedMinutes}분 경과`;
}

export function sortStoreOrderBoard(
  groups: StoreOrderBoard["groups"],
  overflow: StoreOrderBoard["overflow"],
): StoreOrderBoard {
  return {
    groups: [...groups]
      .map((group) => ({
        ...group,
        items: [...group.items].sort((left, right) =>
          left.pickupWindowStart.localeCompare(right.pickupWindowStart)
          || left.orderReference.localeCompare(right.orderReference)),
      }))
      .filter((group) => group.items.length > 0)
      .sort((left, right) => left.pickupBusinessDate.localeCompare(right.pickupBusinessDate)),
    overflow,
  };
}

export function reconcileBoardItem(
  board: StoreOrderBoard,
  changed: StoreOrderBoardItem,
): StoreOrderBoard {
  const groups = board.groups.map((group) => ({
    ...group,
    items: group.items.filter((item) => item.orderReference !== changed.orderReference),
  }));
  if (changed.lane) {
    const existing = groups.find((group) => group.pickupBusinessDate === changed.pickupBusinessDate);
    if (existing) existing.items.push(changed);
    else groups.push({ pickupBusinessDate: changed.pickupBusinessDate, items: [changed] });
  }
  return sortStoreOrderBoard(groups, board.overflow);
}
