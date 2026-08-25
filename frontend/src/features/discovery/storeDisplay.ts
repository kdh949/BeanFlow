import type { components } from "../../api/schema";

type CustomerStoreDisplay = components["schemas"]["CustomerStoreDisplay"];
type NextPickupWindow = components["schemas"]["NextPickupWindow"];
type StoreOperatingDay = components["schemas"]["StoreOperatingDay"];

const operatingStatusLabels: Record<CustomerStoreDisplay["operatingStatus"], string> = {
  OPEN: "영업 중",
  CLOSED: "영업시간 아님",
  UNSPECIFIED: "운영시간 정보 없음",
};

const dayLabels: Record<StoreOperatingDay["dayOfWeek"], string> = {
  MONDAY: "월",
  TUESDAY: "화",
  WEDNESDAY: "수",
  THURSDAY: "목",
  FRIDAY: "금",
  SATURDAY: "토",
  SUNDAY: "일",
};

export function operatingStatusLabel(status: CustomerStoreDisplay["operatingStatus"]) {
  return operatingStatusLabels[status];
}

export function nextPickupLabel(window?: NextPickupWindow) {
  return window
    ? `가장 빠른 픽업 ${pickupTimeLabel(window.startsAt)}`
    : "예약 가능한 픽업 시간 없음";
}

export function pickupTimeLabel(value: string) {
  return new Date(value).toLocaleTimeString("ko-KR", {
    timeZone: "Asia/Seoul",
    hour: "2-digit",
    minute: "2-digit",
  });
}

export function operatingDayLabel(day: StoreOperatingDay) {
  const hours = day.closed ? "휴무" : `${shortTime(day.opensAt)}–${shortTime(day.closesAt)}`;
  return { day: dayLabels[day.dayOfWeek], hours };
}

function shortTime(value?: string) {
  return value?.slice(0, 5) ?? "시간 정보 없음";
}
