import { webcrypto } from "node:crypto";
import { afterEach, expect, it, vi } from "vitest";
import { profileDigest, validProfileValues } from "./supportProfilePayload";
afterEach(() => vi.unstubAllGlobals());
it("matches server vectors including Unicode, absent fields and credential reset intent", async () => {
  vi.stubGlobal("crypto", webcrypto);
  const subject = "81000000-0000-0000-0000-000000000001";
  expect(await profileDigest(subject, 4, "CUSTOMER_PRIMARY_PHONE", { primaryPhone: " 010-1111-2222 " })).toBe("d9ec1687bba3279ad5286ee7469d898e0a0b681952d39fbefadcd5ed6f5dc3a4");
  expect(await profileDigest(subject, 4, "STORE_PUBLIC_PROFILE", { description: "한글|설명", displayName: " " })).toBe("bd7ce2ebc5cd9bd331864e0fe15a381b419922a47d5275f8b8178fc7bb2d50f2");
  expect(await profileDigest(subject, 4, "CUSTOMER_CREDENTIAL_RESET", {})).toBe("ff28749fd5cd3cf880f4e2c7c36b10f9856197da9d31f5b363d36f0e45ac87ab");
  expect(await profileDigest(subject, 5, "CUSTOMER_CREDENTIAL_RESET", {})).not.toBe(await profileDigest(subject, 4, "CUSTOMER_CREDENTIAL_RESET", {}));
});
it("rejects empty edits and invalid opaque reference formatting", () => {
  expect(validProfileValues("STORE_PUBLIC_PROFILE", {})).toBe(false);
  expect(validProfileValues("STORE_PUBLIC_PROFILE", { pickupInstructions: "오른쪽 출입구" })).toBe(true);
  expect(validProfileValues("STORE_SETTLEMENT_ACCOUNT", { accountReference: "123 456 789" })).toBe(false);
  expect(validProfileValues("STORE_SETTLEMENT_ACCOUNT", { accountReference: "account:registered-1" })).toBe(true);
  expect(validProfileValues("COURIER_RELAY_CONTACT", { email: "missing-domain" })).toBe(false);
  expect(validProfileValues("CUSTOMER_CREDENTIAL_RESET", {})).toBe(true);
});
