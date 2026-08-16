import { ApiRequestError } from "../../api/client";

const ITEM_REASONS: Record<string, string> = {
  SOURCE_OPTION_SELECTION_UNAVAILABLE: "옵션 정보를 다시 확인할 수 없어요",
  MENU_REMOVED: "메뉴가 더 이상 없어요",
  MENU_NOT_AVAILABLE: "지금은 판매하지 않아요",
  OPTION_REMOVED: "옵션이 더 이상 없어요",
  OPTION_NOT_AVAILABLE: "옵션을 지금 고를 수 없어요",
  MENU_CONFIGURATION_NOT_AVAILABLE: "이 메뉴·옵션 조합을 지금 주문할 수 없어요",
};

export type ReorderFailure = {
  title: string;
  description: string;
  items: Array<{ lineSequence: number; reason: string; label: string }>;
};

/**
 * The server revalidates every line against the current catalogue and returns a
 * per-item reason. Nothing is reordered partially, so the screen reports exactly
 * what the server refused instead of guessing a substitute.
 */
export function reorderFailure(failure: unknown): ReorderFailure | null {
  if (!(failure instanceof ApiRequestError)) return null;
  switch (failure.code) {
    case "REORDER_ITEMS_UNAVAILABLE": {
      const details = failure.details;
      return {
        title: "지금 그대로 다시 주문할 수 없어요",
        description: "판매 상태가 바뀐 메뉴가 있어요. 매장에서 메뉴를 다시 골라 주세요.",
        items: normalizeItems(details),
      };
    }
    case "REORDER_SOURCE_STATE_INVALID":
      return {
        title: "아직 다시 주문할 수 없는 주문이에요",
        description: "픽업이 끝났거나 종료된 주문만 다시 주문할 수 있어요.",
        items: [],
      };
    case "PICKUP_SLOT_FULL":
      return {
        title: "고른 픽업 시간이 방금 마감됐어요",
        description: "다른 픽업 시간을 골라 주세요.",
        items: [],
      };
    case "STOCK_NOT_AVAILABLE":
      return { title: "재고가 부족해요", description: failure.message, items: [] };
    case "IDEMPOTENCY_REQUEST_IN_PROGRESS":
      return {
        title: "같은 주문을 처리하고 있어요",
        description: "잠시 뒤 주문 내역에서 결과를 확인해 주세요.",
        items: [],
      };
    case "IDEMPOTENCY_MANUAL_REVIEW_REQUIRED":
      return {
        title: "주문 결과를 확인하고 있어요",
        description: "같은 주문을 다시 보내지 마세요. 주문 내역에서 결과를 확인할 수 있어요.",
        items: [],
      };
    default:
      return null;
  }
}

function normalizeItems(details: unknown): ReorderFailure["items"] {
  if (!Array.isArray(details)) return [];
  return details.flatMap((detail) => {
    if (typeof detail !== "object" || detail === null) return [];
    const item = detail as { lineSequence?: unknown; reason?: unknown };
    if (typeof item.reason !== "string") return [];
    const lineSequence = typeof item.lineSequence === "number" ? item.lineSequence : -1;
    return [{
      lineSequence,
      reason: item.reason,
      label: ITEM_REASONS[item.reason] ?? "지금 주문할 수 없어요",
    }];
  });
}
