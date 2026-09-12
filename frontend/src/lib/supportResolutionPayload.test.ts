import { webcrypto } from "node:crypto";
import { afterEach, expect, it, vi } from "vitest";
import { initialResolutionDraft, resolutionDigest, resolutionPlan, validResolutionDraft } from "./supportResolutionPayload";
afterEach(() => vi.unstubAllGlobals());
it("matches the server resolution vector including cost and restoration decisions", async () => {
  vi.stubGlobal("crypto", webcrypto);
  expect(await resolutionDigest("74000000-0000-4000-8000-000000000001", { outcome: "PARTIAL_REFUND", responsibility: "STORE", cashRefundKrw: 3000, restorePoints: true, restoreCoupon: false, settlementAdjustmentKrw: -1500, evidenceDigest: "a".repeat(64) })).toBe("3f1e51131bd4d9a0c1ee345f147b9156f877af0cc548aee252201c0bbefa0604");
});
it("clears financial restoration values for non monetary plans and rejects unsafe integers", async () => {
  vi.stubGlobal("crypto", webcrypto);
  expect(await resolutionPlan({ ...initialResolutionDraft, outcome: "NO_MONETARY_RESOLUTION", cash: "999", restorePoints: true, restoreCoupon: true, settlement: "-500", evidence: "상담 기록" })).toMatchObject({ cashRefundKrw: 0, restorePoints: false, restoreCoupon: false, settlementAdjustmentKrw: null });
  expect(validResolutionDraft({ ...initialResolutionDraft, cash: "9007199254740992", evidence: "상담 기록" })).toBe(false);
  expect(validResolutionDraft({ ...initialResolutionDraft, responsibility: "STORE", cash: "3000", settlement: "1500", evidence: "상담 기록" })).toBe(false);
});
