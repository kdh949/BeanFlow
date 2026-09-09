import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import type { components } from "../../api/schema";
import { SupportTimelinePanel } from "./SupportTimelinePanel";

type Item = components["schemas"]["SupportTimelinePage"]["items"][number];
afterEach(cleanup);

function renderFact(type: Item["type"], state: Item["state"], summary = `${type}:${state}`) {
  render(<SupportTimelinePanel timeline={{ items: [{ itemId: "a8000000-0000-4000-8000-000000000001", source: "SUPPORT", type, state, summary, amountKrw: null, occurredAt: "2026-08-23T09:30:00Z" }], nextCursor: null }} />);
}

describe("server timeline presentation", () => {
  it.each<[Item["type"], Item["state"], string]>([
    ["ORDER_STATE", "COMPLETED", "주문 픽업 완료"],
    ["ORDER_STATE", "ACCEPTED", "주문 접수"],
    ["NOTIFICATION_DELIVERY", "ACCEPTED", "알림 발송 접수"],
    ["PAYMENT_STATE", "APPROVED", "결제 승인 완료"],
    ["REFUND_STATE", "RECONCILING", "환불 결과 재확인 중"],
    ["CASE_STATE", "PENDING_STORE", "상담 매장 응답 대기"],
    ["CASE_ASSIGNMENT", "ASSIGNED", "상담 담당자 배정"],
    ["CASE_INTERACTION", "INBOUND", "상담 수신 기록"],
    ["CASE_NOTE", "RECORDED", "상담 메모 기록"],
    ["SUBJECT_LINK", "UNLINKED", "상담 대상 연결 해제"],
    ["POINT_RESERVATION", "RELEASED", "포인트 예약 해제"],
    ["COUPON_RESERVATION", "USED", "쿠폰 사용"],
    ["PICKUP_RESERVATION", "CONFIRMED", "픽업 예약 확정"],
    ["SETTLEMENT_ITEM", "ITEM_CREATED", "정산 명세 생성"],
    ["SETTLEMENT_ADJUSTMENT", "ADJUSTMENT_RECORDED", "정산 조정 기록"],
    ["OPERATION_AUDIT", "RECORDED", "업무 감사 기록"],
  ])("converts %s:%s using its business context", (type, state, label) => {
    renderFact(type, state);
    expect(screen.getByText(label)).toBeVisible();
    expect(screen.queryByText(`${type}:${state}`)).not.toBeInTheDocument();
  });

  it("uses server state instead of trusting a display-like summary for an unknown refund", () => {
    renderFact("REFUND_STATE", "UNKNOWN", "환불 성공");
    expect(screen.getByText("환불 결과 확인 중")).toBeVisible();
    expect(screen.queryByText("환불 성공")).not.toBeInTheDocument();
  });

  it("keeps unsupported server values explicit without exposing their machine summary", () => {
    renderFact("FUTURE_TYPE" as Item["type"], "FUTURE_STATE" as Item["state"]);
    expect(screen.getByText("이력 상태 확인 필요")).toBeVisible();
    expect(screen.queryByText(/FUTURE_/)).not.toBeInTheDocument();
  });
});
