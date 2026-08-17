import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { merchantApi } from "../../api/merchantClient";
import { DisputeFilingPanel } from "./DisputeFilingPanel";

const settlementItemId = "91000000-0000-4000-8000-000000000001";

beforeEach(() => {
  document.cookie = "BEANFLOW_MERCHANT_XSRF=test-merchant-csrf; path=/";
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  document.cookie = "BEANFLOW_MERCHANT_XSRF=; Max-Age=0; path=/";
});

describe("dispute filing success feedback", () => {
  it("keeps the confirmation on screen after filing instead of unmounting immediately", async () => {
    vi.spyOn(merchantApi, "POST").mockResolvedValue({
      data: {
        disputeId: "dispute-1",
        settlementItemId,
        state: "FILED",
        heldAmountKrw: 3_500,
        currency: "KRW",
        filedAt: "2026-08-16T10:00:00+09:00",
      },
      response: new Response(null, { status: 201 }),
    } as never);
    const onFiled = vi.fn();
    const onClose = vi.fn();

    render(<DisputeFilingPanel settlementItemId={settlementItemId} onFiled={onFiled} onClose={onClose} />);
    await userEvent.type(screen.getByLabelText("사유"), "정산 금액이 다릅니다");
    await userEvent.type(screen.getByLabelText("증빙 위치 (한 줄에 하나)"), "storage://receipt-1");
    await userEvent.click(screen.getByRole("button", { name: "이의제기 접수" }));

    expect(await screen.findByRole("status")).toHaveTextContent("이의제기를 접수했습니다");
    expect(onFiled).toHaveBeenCalledTimes(1);
    expect(onClose).not.toHaveBeenCalled();

    // The panel stays mounted and showing the confirmation until the operator
    // explicitly dismisses it — the caller no longer unmounts it on `onFiled`.
    expect(screen.getByRole("status")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "확인" }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
