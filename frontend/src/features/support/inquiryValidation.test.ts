import { describe, expect, it } from "vitest";
import { ApiRequestError } from "../../api/client";
import { inquiryFieldError } from "./inquiryValidation";

describe("inquiry field error presentation", () => {
  it("renders fixed copy without reflecting server messages or sensitive details", () => {
    const failure = new ApiRequestError(400, "INVALID_REQUEST", "unsafe-server-value", undefined, [{ field: "content", reason: "INVALID_VALUE" }]);
    expect(inquiryFieldError(failure, "content")).toContain("2,000자");
    expect(inquiryFieldError(failure, "content")).not.toContain("unsafe-server-value");
    expect(inquiryFieldError(failure, "title")).toBeUndefined();
  });
  it("does not label unrelated failures or unknown fields as invalid user input", () => {
    expect(inquiryFieldError(new ApiRequestError(503, "DEPENDENCY_UNAVAILABLE", "failed", undefined, [{ field: "title", reason: "INVALID_VALUE" }]), "title")).toBeUndefined();
    expect(inquiryFieldError(new ApiRequestError(400, "INVALID_REQUEST", "failed", undefined, [{ field: "unknown", reason: "INVALID_VALUE" }]), "content")).toBeUndefined();
    expect(inquiryFieldError(new TypeError("network"), "content")).toBeUndefined();
  });
});
