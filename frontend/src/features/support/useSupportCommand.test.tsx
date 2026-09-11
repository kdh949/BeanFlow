import { act, renderHook, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ApiRequestError } from "../../api/client";
import { useSupportCommand } from "./useSupportCommand";

describe("useSupportCommand", () => {
  it.each([403, 409])("keeps the original unknown attempt after a later %s until confirmed", async status => {
    const run = vi.fn().mockRejectedValueOnce(new TypeError("Lost response"))
      .mockRejectedValueOnce(new ApiRequestError(status, "ACCESS_DENIED", "Denied"))
      .mockResolvedValueOnce({ result: "recorded" });
    const success = vi.fn(), replacement = vi.fn();
    const { result } = renderHook(() => useSupportCommand(() => undefined));
    act(() => result.current.submit("original target and body", run, success));
    await waitFor(() => expect(result.current.pending).toBe(true));
    await act(async () => { await result.current.retry(); });
    expect(result.current.pending).toBe(true);
    act(() => result.current.submit("different target", replacement, vi.fn()));
    expect(replacement).not.toHaveBeenCalled();
    await act(async () => { await result.current.retry(); });
    expect(result.current.pending).toBe(false);
    expect(success).toHaveBeenCalledTimes(1);
    expect(run).toHaveBeenCalledTimes(3);
    expect(new Set(run.mock.calls.map(([key]) => key)).size).toBe(1);
  });

  it("allows correction after a definitive first denial without claiming success", async () => {
    const run = vi.fn().mockRejectedValue(new ApiRequestError(403, "ACCESS_DENIED", "Denied"));
    const success = vi.fn();
    const { result } = renderHook(() => useSupportCommand(() => undefined));
    act(() => result.current.submit("first", run, success));
    await waitFor(() => expect(result.current.failure).toBeInstanceOf(ApiRequestError));
    expect(result.current.pending).toBe(false);
    expect(success).not.toHaveBeenCalled();
    const corrected = vi.fn().mockResolvedValue({});
    act(() => result.current.submit("corrected", corrected, success));
    await waitFor(() => expect(success).toHaveBeenCalledTimes(1));
    expect(corrected).toHaveBeenCalledTimes(1);
  });
});
