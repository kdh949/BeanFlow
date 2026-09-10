import { expect, it } from "vitest";
import { seoulInputValue, seoulInstant } from "./seoulDateTime";
it("preserves Korean business time across date boundaries independently of browser timezone", () => {
  expect(seoulInstant("2026-10-01T00:15")).toBe("2026-09-30T15:15:00.000Z");
  expect(seoulInputValue("2026-09-30T15:15:00Z")).toBe("2026-10-01T00:15");
});
it("rejects empty and normalized invalid calendar dates", () => { expect(() => seoulInstant("")).toThrow(); expect(() => seoulInstant("2026-02-30T09:00")).toThrow(); });
