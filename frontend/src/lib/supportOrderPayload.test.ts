import { webcrypto } from "node:crypto";
import { afterEach, expect, it, vi } from "vitest";
import { orderChangeDigest, supportDigest } from "./supportOrderPayload";
afterEach(() => vi.unstubAllGlobals());
it("matches the server canonical cancellation and reschedule vectors", async () => {
  vi.stubGlobal("crypto", webcrypto);
  const orderId = "74000000-0000-4000-8000-000000000001";
  const slotId = "74000000-0000-4000-8000-000000000002";
  expect(await orderChangeDigest("ORDER_CANCELLATION", orderId, "CHANGED_MIND", "ignored")).toBe("a6751d0986f09a852783b5b3b49c58b1d49f4517cbfcccbfba3f27132b2542cf");
  expect(await orderChangeDigest("PICKUP_RESCHEDULE", orderId, "OTHER", slotId)).toBe("9d55ae151607f8deaae9e583267419677a22fd9a972829b151af3efc82a3435f");
  expect(await orderChangeDigest("ORDER_CANCELLATION", orderId, "OTHER", "")).not.toBe(await orderChangeDigest("ORDER_CANCELLATION", orderId, "CHANGED_MIND", ""));
  expect(await supportDigest("abc")).toBe("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
});
