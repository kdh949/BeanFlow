import { webcrypto } from "node:crypto";
import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { ApiRequestError } from "../../api/client";
import { authToken } from "../../auth/session";
import { useSupportCommand } from "./useSupportCommand";

beforeEach(() => { vi.stubGlobal("crypto", webcrypto); sessionStorage.clear(); setActor("first-operator"); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });
const fingerprint = JSON.stringify({ caseId: "private-case-id", operation: "note", content: "private-note-content" });
const success = () => {};
function setActor(sub: string) { authToken.set(`test.${btoa(JSON.stringify({ sub, iss: "test" }))}.fixture`); }

it("records only opaque identity before dispatch and reuses it after unmount during the request", async () => {
  const firstRun = vi.fn(async (_key: string) => new Promise<void>(() => {}));
  const first = renderHook(() => useSupportCommand("case:private-case-id", success));
  act(() => first.result.current.submit(fingerprint, firstRun, success));
  await waitFor(() => expect(firstRun).toHaveBeenCalledOnce());
  const serialized = JSON.stringify(Array.from({ length: sessionStorage.length }, (_, index) => { const key = sessionStorage.key(index)!; return [key, sessionStorage.getItem(key)]; }));
  expect(serialized).not.toContain("private-case-id"); expect(serialized).not.toContain("private-note-content");
  const key = firstRun.mock.calls[0]![0]; expect(serialized).toContain(key);
  first.unmount();
  const replay = vi.fn(async (_key: string) => {});
  const second = renderHook(() => useSupportCommand("case:private-case-id", success));
  act(() => second.result.current.submit(fingerprint, replay, success));
  await waitFor(() => expect(replay).toHaveBeenCalledWith(key));
  await waitFor(() => expect(second.result.current.busy).toBe(false));
  expect(sessionStorage.length).toBe(0);
});

it("keeps an earlier unknown outcome pending after a later denial", async () => {
  const run = vi.fn<(key: string) => Promise<void>>().mockRejectedValueOnce(new TypeError("lost response"))
    .mockRejectedValueOnce(new ApiRequestError(403, "ACCESS_DENIED", "revoked")).mockResolvedValueOnce();
  const hook = renderHook(() => useSupportCommand("note", success));
  act(() => hook.result.current.submit(fingerprint, run, success));
  await waitFor(() => expect(hook.result.current.pending).toBe(true));
  await act(async () => hook.result.current.retry());
  expect(hook.result.current.pending).toBe(true); expect(sessionStorage.length).toBe(1);
  await act(async () => hook.result.current.retry());
  expect(run.mock.calls.map(([key]) => key)).toEqual(Array(3).fill(run.mock.calls[0]![0]));
  expect(hook.result.current.pending).toBe(false); expect(sessionStorage.length).toBe(0);
});

it("does not dispatch when durable identity cannot be written", async () => {
  vi.spyOn(sessionStorage, "setItem").mockImplementation(() => { throw new DOMException("quota", "QuotaExceededError"); });
  const run = vi.fn(); const hook = renderHook(() => useSupportCommand("note", success));
  act(() => hook.result.current.submit(fingerprint, run, success));
  await waitFor(() => expect(hook.result.current.failure).toBeInstanceOf(ApiRequestError));
  expect(run).not.toHaveBeenCalled(); expect(hook.result.current.busy).toBe(false);
});

it("separates workflows and rejects duplicate clicks while preparing identity", async () => {
  const run = vi.fn(async (_key: string) => { throw new TypeError("lost"); });
  const first = renderHook(() => useSupportCommand("first-workflow", success));
  act(() => { first.result.current.submit(fingerprint, run, success); first.result.current.submit(fingerprint, run, success); });
  await waitFor(() => expect(first.result.current.pending).toBe(true));
  expect(run).toHaveBeenCalledOnce();
  const second = renderHook(() => useSupportCommand("second-workflow", success));
  act(() => second.result.current.submit(fingerprint, run, success));
  await waitFor(() => expect(run).toHaveBeenCalledTimes(2));
  expect(run.mock.calls[0]![0]).not.toBe(run.mock.calls[1]![0]);
});


it("keeps actor journals separate across logout and sign-in", async () => {
  const run = vi.fn(async (_key: string) => { throw new TypeError("lost"); });
  const first = renderHook(() => useSupportCommand("note", success));
  act(() => first.result.current.submit(fingerprint, run, success));
  await waitFor(() => expect(first.result.current.pending).toBe(true));
  const firstKey = run.mock.calls[0]![0];
  first.unmount(); setActor("second-operator");
  const second = renderHook(() => useSupportCommand("note", success));
  act(() => second.result.current.submit(fingerprint, run, success));
  await waitFor(() => expect(run).toHaveBeenCalledTimes(2));
  expect(run.mock.calls[1]![0]).not.toBe(firstKey);
  second.unmount(); setActor("first-operator");
  const original = renderHook(() => useSupportCommand("note", success));
  act(() => original.result.current.submit(fingerprint, run, success));
  await waitFor(() => expect(run).toHaveBeenCalledTimes(3));
  expect(run.mock.calls[2]![0]).toBe(firstKey);
});
