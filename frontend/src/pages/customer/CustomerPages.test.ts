import { describe, expect, it } from "vitest";
import { failureMessage } from "./CustomerPages";

describe("Toss payment failure copy", () => {
  it("maps known public SDK codes to customer copy", () => {
    expect(failureMessage("PAY_PROCESS_CANCELED")).toContain("취소");
    expect(failureMessage("REJECT_CARD_COMPANY")).toContain("카드사");
  });

  it("never renders an untrusted provider message", () => {
    const untrusted = "<script>alert('card')</script>";
    expect(failureMessage(untrusted)).not.toContain(untrusted);
    expect(failureMessage(untrusted)).toContain("안전하게 다시 시도");
  });
});
