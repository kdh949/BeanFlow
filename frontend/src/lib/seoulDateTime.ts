/** Values for native local date/time controls always represent Korean business time. */
export function seoulInputValue(value: string | number | Date): string {
  const date = new Date(value);
  return new Date(date.getTime() + 9 * 60 * 60 * 1000).toISOString().slice(0, 16);
}
export function seoulInstant(value: string): string {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value)) throw new Error("한국 시간의 날짜와 시각을 입력해 주세요.");
  const instant = new Date(`${value}:00+09:00`).toISOString();
  if (seoulInputValue(instant) !== value) throw new Error("유효한 날짜와 시각을 입력해 주세요.");
  return instant;
}
