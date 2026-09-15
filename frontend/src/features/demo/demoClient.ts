import { ApiRequestError, cookieValue } from "../../api/client";
import type { components } from "../../api/schema";
export type DemoConfig = components["schemas"]["DemoConfig"];
export type DemoSession = components["schemas"]["DemoSession"];
let csrfInFlight: Promise<string> | null = null;
async function request<T>(path: string, method = "GET", body?: unknown, key?: string): Promise<T> {
  const headers: Record<string, string> = { Accept: "application/json" };
  if (method !== "GET") headers["X-BEANFLOW-CSRF"] = await csrf();
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (key) headers["Idempotency-Key"] = key;
  const response = await fetch(`/api/v1/demo${path}`, { method, headers, credentials: "same-origin", cache: "no-store", ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
  if (!response.ok) {
    const error = await response.json().catch(() => ({})) as { code?: string };
    throw new ApiRequestError(response.status, error.code ?? "DEPENDENCY_UNAVAILABLE", "체험 요청을 처리하지 못했습니다.");
  }
  if (response.status === 204) return null as T;
  return await response.json() as T;
}
async function csrf(force = false): Promise<string> {
  const current = cookieValue("BEANFLOW_DEMO_XSRF");
  if (current && !force) return current;
  if (!csrfInFlight) csrfInFlight = request<{ token: string }>("/csrf").then(({ token }) => {
    if (!token) throw new ApiRequestError(503, "DEPENDENCY_UNAVAILABLE", "체험 보안 토큰을 확인하지 못했습니다.");
    return token;
  }).finally(() => { csrfInFlight = null; });
  return csrfInFlight;
}
export const demoApi = {
  config: () => request<DemoConfig>("/config"),
  current: async () => {
    await csrf();
    try { return await request<DemoSession | null>("/session"); }
    catch (error) {
      if (!(error instanceof ApiRequestError) || error.code !== "DEMO_SESSION_NOT_FOUND") throw error;
      await csrf(true);
      return request<DemoSession | null>("/session");
    }
  },
  start: (mode: "GUIDED" | "DIRECT", key: string) => request<DemoSession>("/sessions", "POST", { mode }, key),
  resume: () => request<DemoSession>("/session/resume", "POST"),
  sample: (key: string) => request<DemoSession>("/session/orders", "POST", undefined, key),
  track: (orderReference: string, key: string) => request<DemoSession>("/session/order", "POST", { orderReference }, key),
  end: () => request<null>("/session", "DELETE"),
};
export function demoFailureCopy(error: unknown): string {
  if (error instanceof ApiRequestError) {
    switch (error.code) {
      case "DEMO_SESSION_CONFLICT": return "현재 로그인한 고객·점주 계정이 있어요. 해당 계정에서 로그아웃한 뒤 체험을 시작해 주세요.";
      case "DEMO_ALREADY_ACTIVE": return "이미 진행 중인 체험이 있어요. 체험 이어하기를 선택해 주세요.";
      case "DEMO_QUOTA_REACHED": return "지금은 체험 공간이 모두 사용 중이거나 오늘의 체험 횟수를 채웠어요. 잠시 후 다시 방문해 주세요.";
      case "DEMO_SAMPLE_LIMIT": return "이 공간의 샘플 주문을 모두 사용했어요. 체험을 마친 뒤 새 공간으로 시작해 주세요.";
      case "DEMO_ORDER_ACTIVE": return "아직 진행 중인 주문이 있어요. 현재 주문을 마친 뒤 새 주문으로 시작해 주세요.";
      case "DEMO_EXPIRED": return "체험 시간이 끝났어요. 새 체험 공간으로 다시 시작해 주세요.";
    }
  }
  return "체험 상태를 확인하지 못했어요. 요청 결과를 확인할 수 있도록 같은 요청으로 다시 시도해 주세요.";
}
