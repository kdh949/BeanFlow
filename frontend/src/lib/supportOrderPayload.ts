import type { components } from "../api/schema";
export type OrderChangeAction = "ORDER_CANCELLATION" | "PICKUP_RESCHEDULE";
export type CancellationReason = components["schemas"]["CancellationReasonCode"];
export const orderActionLabels = { ORDER_CANCELLATION: "주문 취소", PICKUP_RESCHEDULE: "픽업 시간 변경" };
export const cancellationReasonLabels: Record<CancellationReason, string> = { CHANGED_MIND: "고객의 단순 변심", ORDER_MISTAKE: "주문 실수", WAIT_TOO_LONG: "대기 시간 초과", PICKUP_TIME_CONFLICT: "픽업 일정 변경", PAYMENT_ISSUE: "결제 문제", OTHER: "기타" };
const encoder = new TextEncoder();
export async function supportDigest(text: string): Promise<string> {
  return Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", encoder.encode(text))), byte => byte.toString(16).padStart(2, "0")).join("");
}
/** Matches SupportCommandPayloadCanonicalizer's ordered UTF-8 length prefixes, including null fields. */
export function orderChangeCanonical(action: OrderChangeAction, orderId: string, reason: CancellationReason, slotId: string): string {
  return ["support-command-payload/v1", "operation", "enum", "SUPPORT_ORDER_CHANGE_ACTION_V1", "action", "enum", action,
    "orderId", "uuid", orderId.toLowerCase(), "cancellationReasonCode", "enum", action === "ORDER_CANCELLATION" ? reason : null,
    "newPickupSlotId", "uuid", action === "PICKUP_RESCHEDULE" ? slotId.toLowerCase() : null]
    .map(value => value === null ? "-1:" : `${encoder.encode(value).length}:${value}`).join("");
}
export async function orderChangeDigest(action: OrderChangeAction, orderId: string, reason: CancellationReason, slotId: string) {
  return supportDigest(orderChangeCanonical(action, orderId, reason, slotId));
}
