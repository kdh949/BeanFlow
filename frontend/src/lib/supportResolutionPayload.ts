import type { components } from "../api/schema";
import { supportDigest } from "./supportOrderPayload";
export type ResolutionPlan = Pick<components["schemas"]["CreatePostAcceptanceResolutionRequest"], "outcome" | "responsibility" | "cashRefundKrw" | "restorePoints" | "restoreCoupon" | "settlementAdjustmentKrw" | "evidenceDigest">;
export type ResolutionDraft = { outcome: ResolutionPlan["outcome"]; responsibility: ResolutionPlan["responsibility"]; cash: string; restorePoints: boolean; restoreCoupon: boolean; settlement: string; evidence: string };
export const initialResolutionDraft: ResolutionDraft = { outcome: "PARTIAL_REFUND", responsibility: "UNDETERMINED", cash: "", restorePoints: false, restoreCoupon: false, settlement: "", evidence: "" };
export const resolutionOutcomeLabels: Record<ResolutionPlan["outcome"], string> = { FULL_REFUND: "전액 환불", PARTIAL_REFUND: "일부 환불", NO_MONETARY_RESOLUTION: "금전 처리 없이 해결", MANUAL_SETTLEMENT_REVIEW: "정산 수동 검토" };
export const resolutionResponsibilityLabels: Record<ResolutionPlan["responsibility"], string> = { CUSTOMER: "고객 책임", STORE: "매장 부담", PLATFORM: "플랫폼 부담", SHARED: "매장·플랫폼 공동 부담", UNDETERMINED: "책임 미확정" };
export const isRefundResolution = (outcome: ResolutionPlan["outcome"]) => outcome === "FULL_REFUND" || outcome === "PARTIAL_REFUND";
export const isStoreResolution = (responsibility: ResolutionPlan["responsibility"]) => responsibility === "STORE" || responsibility === "SHARED";
export function validResolutionDraft(draft: ResolutionDraft) {
  const refund = isRefundResolution(draft.outcome), store = isStoreResolution(draft.responsibility);
  return !!draft.evidence.trim() && (!refund || (/^\d+$/.test(draft.cash) && Number.isSafeInteger(Number(draft.cash)) && Number(draft.cash) > 0)) && (!store || draft.outcome === "MANUAL_SETTLEMENT_REVIEW" || (/^-\d+$/.test(draft.settlement) && Number.isSafeInteger(Number(draft.settlement)) && Number(draft.settlement) < 0));
}
export async function resolutionPlan(draft: ResolutionDraft): Promise<ResolutionPlan> {
  const refund = isRefundResolution(draft.outcome);
  return { outcome: draft.outcome, responsibility: draft.responsibility, cashRefundKrw: refund ? Number(draft.cash) : 0, restorePoints: refund && draft.restorePoints, restoreCoupon: refund && draft.restoreCoupon, settlementAdjustmentKrw: isStoreResolution(draft.responsibility) && draft.outcome !== "MANUAL_SETTLEMENT_REVIEW" ? Number(draft.settlement) : null, evidenceDigest: await supportDigest(draft.evidence.trim()) };
}
/** Exact ordered scalar encoding shared with the server's resolution action canonicalizer. */
export function resolutionCanonical(orderId: string, plan: ResolutionPlan): string {
  const encoder = new TextEncoder();
  const fields: [string, string, unknown][] = [["orderId", "uuid", orderId.toLowerCase()], ["outcome", "enum", plan.outcome], ["responsibility", "enum", plan.responsibility], ["cashRefundKrw", "int64", plan.cashRefundKrw], ["restorePoints", "boolean", plan.restorePoints], ["restoreCoupon", "boolean", plan.restoreCoupon], ["settlementAdjustmentKrw", "int64", plan.settlementAdjustmentKrw], ["evidenceDigest", "sha256", plan.evidenceDigest]];
  return ["support-command-payload/v1", "operation", "enum", "POST_ACCEPTANCE_RESOLUTION_ACTION_V1", ...fields.flat()].map(value => value == null ? "-1:" : `${encoder.encode(String(value)).length}:${String(value)}`).join("");
}
export const resolutionDigest = (orderId: string, plan: ResolutionPlan) => supportDigest(resolutionCanonical(orderId, plan));
