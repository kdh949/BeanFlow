import { describe, expect, it } from "vitest";
import { nextPickupLabel, operatingDayLabel, operatingStatusLabel, pickupTimeLabel } from "./storeDisplay";

describe("customer store display copy", () => {
  it("formats pickup timestamps in the contract timezone", () => {
    expect(pickupTimeLabel("2026-08-15T03:20:00Z")).toBe("오후 12:20");
    expect(nextPickupLabel({ startsAt: "2026-08-15T03:20:00Z", endsAt: "2026-08-15T03:30:00Z" }))
      .toBe("가장 빠른 픽업 오후 12:20");
  });

  it("preserves missing schedule and pickup information", () => {
    expect(operatingStatusLabel("UNSPECIFIED")).toBe("운영시간 정보 없음");
    expect(nextPickupLabel()).toBe("예약 가능한 픽업 시간 없음");
  });

  it("renders open and closed operating days without inventing times", () => {
    expect(operatingDayLabel({ dayOfWeek: "MONDAY", closed: false, opensAt: "08:00:00", closesAt: "20:00:00" }))
      .toEqual({ day: "월", hours: "08:00–20:00" });
    expect(operatingDayLabel({ dayOfWeek: "SUNDAY", closed: true })).toEqual({ day: "일", hours: "휴무" });
  });
});
