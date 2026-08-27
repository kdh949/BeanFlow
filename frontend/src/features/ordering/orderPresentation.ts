import type { components } from "../../api/schema";

type CustomerOrderDetail = components["schemas"]["CustomerOrderDetail"];

export type OrderTimelineModel =
  | { kind: "pending"; activeIndex: null }
  | { kind: "progress"; activeIndex: number }
  | { kind: "terminal"; activeIndex: null };

export function pickupNumberNote(status: CustomerOrderDetail["status"]): string | null {
  if (status === "READY") return "픽업대에서 번호를 확인해 주세요.";
  if (["PAID", "ACCEPTED", "PREPARING"].includes(status)) return "준비가 끝나면 이 번호로 알려드릴게요.";
  return null;
}

export function customerOrderTimelineModel(status: CustomerOrderDetail["status"]): OrderTimelineModel {
  if (status === "PENDING_PAYMENT") return { kind: "pending", activeIndex: null };
  if (["CANCELLED", "REJECTED", "EXPIRED"].includes(status)) return { kind: "terminal", activeIndex: null };
  const activeIndex = ["PAID", "ACCEPTED", "PREPARING", "READY", "COMPLETED"].indexOf(status);
  return { kind: "progress", activeIndex };
}
