import { describe, expect, it } from "vitest";
import { shortDateTime, shortTime } from "./format";

describe("서울 시간 표시", () => {
  it("실행 환경의 timezone과 무관하게 Asia/Seoul을 사용한다", () => {
    const instant = new Date("2026-08-15T03:20:00Z");

    expect(shortDateTime.resolvedOptions().timeZone).toBe("Asia/Seoul");
    expect(shortTime.resolvedOptions().timeZone).toBe("Asia/Seoul");
    expect(shortTime.format(instant)).toBe("오후 12:20");
  });
});
