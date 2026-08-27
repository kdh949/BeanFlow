import { ApiRequestError } from "../../api/client";

export type ConflictGuidance = {
  title: string;
  description: string;
  /** What the customer should do next. The client never edits the cart by itself. */
  recovery: "recheck-slots" | "recheck-menu" | "wait" | "contact" | "retry";
};

/**
 * Server conflicts are explained, never repaired silently. The cart keeps what
 * the customer chose and the customer decides what to change.
 */
export function orderConflictGuidance(failure: unknown): ConflictGuidance | null {
  if (!(failure instanceof ApiRequestError)) return null;
  switch (failure.code) {
    case "PICKUP_SLOT_FULL":
      return {
        title: "고른 픽업 시간이 방금 마감됐어요",
        description: "다른 픽업 시간을 골라 주세요. 담아둔 메뉴는 그대로 있어요.",
        recovery: "recheck-slots",
      };
    case "STOCK_NOT_AVAILABLE":
      return {
        title: "재고가 부족한 메뉴가 있어요",
        description: "현재 재고를 확인한 뒤 메뉴 수량이나 구성을 다시 선택해 주세요.",
        recovery: "recheck-menu",
      };
    case "MENU_CONFIGURATION_NOT_AVAILABLE":
      return {
        title: "지금 주문할 수 없는 메뉴 구성이에요",
        description: "메뉴와 옵션의 판매 상태가 바뀌었습니다. 구성을 다시 선택해 주세요.",
        recovery: "recheck-menu",
      };
    case "COUPON_NOT_AVAILABLE":
    case "POINT_BALANCE_INSUFFICIENT":
      return {
        title: "혜택을 적용할 수 없어요",
        description: "쿠폰 또는 포인트 적용 조건을 확인한 뒤 다시 선택해 주세요.",
        recovery: "recheck-menu",
      };
    case "RESERVATION_EXPIRED":
      return {
        title: "결제 시간이 지났어요",
        description: "주문을 다시 만들어 주세요.",
        recovery: "retry",
      };
    case "ORDER_QUOTE_STALE":
      return {
        title: "주문 금액과 조건이 변경됐어요",
        description: "서버가 다시 계산한 내용을 확인해야 주문을 다시 보낼 수 있어요.",
        recovery: "retry",
      };
    case "IDEMPOTENCY_REQUEST_IN_PROGRESS":
      return {
        title: "같은 주문을 처리하고 있어요",
        description: "잠시 뒤 다시 확인해 주세요. 새 주문을 만들지 않아도 됩니다.",
        recovery: "wait",
      };
    case "IDEMPOTENCY_MANUAL_REVIEW_REQUIRED":
      return {
        title: "주문 결과를 확인하고 있어요",
        description: "같은 주문을 다시 보내지 마세요. 주문 내역에서 결과를 확인할 수 있어요.",
        recovery: "contact",
      };
    default:
      return null;
  }
}

/**
 * Only a payload-mismatch conflict invalidates the current key automatically.
 * ORDER_QUOTE_STALE stays bound to its terminal key until the customer explicitly
 * accepts currentQuote; that confirmation rotates the intent in CartPage.
 */
export function shouldRotateIdempotencyKey(failure: unknown): boolean {
  return failure instanceof ApiRequestError && failure.code === "IDEMPOTENCY_KEY_REUSED";
}
