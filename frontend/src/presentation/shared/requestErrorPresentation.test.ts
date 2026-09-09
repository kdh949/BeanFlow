import { describe, expect, it } from "vitest";
import { ApiRequestError } from "../../api/client";
import { requestErrorPresentation } from "./requestErrorPresentation";

describe("requestErrorPresentation", () => {
  it("does not expose an arbitrary Error message", () => {
    const result = requestErrorPresentation(new Error("postgres://internal-user:secret@db/private"));

    expect(result.description).toBe("네트워크 연결을 확인하고 다시 시도해 주세요.");
    expect(JSON.stringify(result)).not.toContain("secret");
  });

  it("maps a known API error and preserves only its correlation reference", () => {
    const result = requestErrorPresentation(
      new ApiRequestError(400, "PAYMENT_CALLBACK_MISMATCH", "provider raw message", "REQ-42"),
    );

    expect(result).toEqual({
      title: "결제 정보를 확인할 수 없습니다",
      description: "결제창에서 돌아온 정보가 주문과 일치하지 않습니다. 주문 상태를 확인해 주세요.",
      reference: "REQ-42",
    });
    expect(JSON.stringify(result)).not.toContain("provider raw message");
  });

  it("uses fixed copy for an unknown API error", () => {
    const result = requestErrorPresentation(
      new ApiRequestError(500, "UNEXPECTED_DATABASE_FAILURE", "table customer_secret is missing", "REQ-99"),
    );

    expect(result.description).toBe("네트워크 연결을 확인하고 다시 시도해 주세요.");
    expect(result.reference).toBe("REQ-99");
    expect(JSON.stringify(result)).not.toContain("customer_secret");
  });
});
