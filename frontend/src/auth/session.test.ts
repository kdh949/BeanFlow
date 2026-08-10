import { describe, expect, it, vi } from "vitest";
import { authToken } from "./session";

describe("auth token storage", () => {
  it("normalizes the optional Bearer prefix", () => {
    authToken.set("  Bearer token-value  ");
    expect(authToken.get()).toBe("token-value");
  });

  it("notifies subscribers when the token changes", () => {
    const listener = vi.fn();
    const unsubscribe = authToken.subscribe(listener);
    authToken.set("token-value");
    unsubscribe();
    authToken.clear();
    expect(listener).toHaveBeenCalledTimes(1);
  });
});
