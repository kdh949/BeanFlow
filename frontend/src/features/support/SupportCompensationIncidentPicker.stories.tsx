import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent, waitFor } from "storybook/test";
import { http, HttpResponse, delay } from "msw";
import MockDate from "mockdate";
import { SupportCompensationIncidentPicker } from "./SupportCompensationIncidentPicker";
const id = "75000000-0000-4000-8000-000000000001";
const incident = { incidentId: id, category: "COMPENSATION", occurredAt: "2026-09-11T08:00:00Z", createdAt: "2026-09-11T09:00:00Z", source: "REGISTERED", benefitIssued: false };
const meta = {
  title: "Patterns/Support/Compensation incident selection", component: SupportCompensationIncidentPicker, tags: ["autodocs"],
  args: { caseId: id, sessionId: id, orderId: id, disabled: false, onSelect: fn(), onBusyChange: fn() },
  beforeEach() { MockDate.set("2026-09-11T09:04:00Z"); return () => MockDate.reset(); },
  parameters: { a11y: { test: "error" }, docs: { description: { component: "현재 본인확인 고객과 주문에 해당하는 기존 사고를 선택하거나, 별개 사고의 발생 시각을 확인하고 멱등 등록합니다. 이미 지급한 사고를 새 사고로 바꾸지 않습니다." }, story: { inline: false, height: "900px" } }, msw: { handlers: [http.get("/api/v1/support/cases/:caseId/compensation-incidents", () => HttpResponse.json({ items: [incident], nextCursor: null }))] } },
} satisfies Meta<typeof SupportCompensationIncidentPicker>;
export default meta;
type Story = StoryObj<typeof meta>;
export const SelectExisting: Story = { play: async ({ canvas, args }) => { await userEvent.click(await canvas.findByRole("button", { name: "이 사고 선택" })); await waitFor(() => expect(args.onSelect).toHaveBeenCalledWith(incident)); await expect(canvas.queryByLabelText("사고 ID")).not.toBeInTheDocument(); } };
export const RegisterDistinct: Story = { play: async ({ canvas, args, msw }) => {
  msw.use(http.post("/api/v1/support/cases/:caseId/compensation-incidents", async ({ request }) => { expect(await request.json()).toEqual({ verificationSessionId: id, orderId: id, occurredAt: "2026-09-11T08:00:00.000Z" }); return HttpResponse.json(incident, { status: 201 }); }));
  await userEvent.click(await canvas.findByRole("button", { name: "별개의 새 사고 등록" }));
  await userEvent.type(canvas.getByLabelText("사고 발생 시각 (한국 시간)"), "2026-09-11T17:00");
  await userEvent.click(canvas.getByRole("checkbox", { name: /^기존 사고와 다른 사고임을 확인했습니다/ }));
  await userEvent.click(canvas.getByRole("button", { name: "확인한 새 사고 등록" }));
  await waitFor(() => expect(args.onSelect).toHaveBeenCalledWith(incident));
} };
export const LostRegistration: Story = { play: async ({ canvas, args, msw }) => {
  const keys: string[] = []; msw.use(http.post("/api/v1/support/cases/:caseId/compensation-incidents", ({ request }) => { keys.push(request.headers.get("Idempotency-Key")!); if (keys.length === 1) return HttpResponse.error(); expect(keys[1]).toBe(keys[0]); return HttpResponse.json(incident, { status: 201 }); }));
  await userEvent.click(await canvas.findByRole("button", { name: "별개의 새 사고 등록" })); await userEvent.type(canvas.getByLabelText("사고 발생 시각 (한국 시간)"), "2026-09-11T17:00"); await userEvent.click(canvas.getByRole("checkbox", { name: /^기존 사고와 다른 사고임을 확인했습니다/ })); await userEvent.click(canvas.getByRole("button", { name: "확인한 새 사고 등록" }));
  await expect(await canvas.findByRole("button", { name: "같은 사고 등록 결과 확인" })).toBeEnabled(); await expect(canvas.getByLabelText("사고 발생 시각 (한국 시간)")).toBeDisabled(); await expect(canvas.getByRole("button", { name: "이 사고 선택" })).toBeDisabled(); await waitFor(() => expect(args.onBusyChange).toHaveBeenCalledWith(true)); await userEvent.click(canvas.getByRole("button", { name: "같은 사고 등록 결과 확인" })); await waitFor(() => expect(args.onSelect).toHaveBeenCalledWith(incident));
} };
export const Empty: Story = { parameters: { msw: { handlers: [http.get("/api/v1/support/cases/:caseId/compensation-incidents", () => HttpResponse.json({ items: [], nextCursor: null }))] } }, play: async ({ canvas }) => { await expect(await canvas.findByText("이 고객과 주문의 보상 사고가 없습니다")).toBeVisible(); } };
export const Unavailable: Story = { parameters: { msw: { handlers: [http.get("/api/v1/support/cases/:caseId/compensation-incidents", () => HttpResponse.json({ code: "DEPENDENCY_UNAVAILABLE", correlationId: "INCIDENT-READ" }, { status: 503 }))] } }, play: async ({ canvas }) => { await expect(await canvas.findByText("문의 코드 INCIDENT-READ")).toBeVisible(); await expect(canvas.queryByText("이 고객과 주문의 보상 사고가 없습니다")).not.toBeInTheDocument(); } };
export const Loading: Story = { parameters: { msw: { handlers: [http.get("/api/v1/support/cases/:caseId/compensation-incidents", async () => { await delay("infinite"); })] } }, play: async ({ canvas }) => { await expect(await canvas.findByText("기존 보상 사고를 확인하는 중")).toBeVisible(); } };
export const AlreadyIssued: Story = { parameters: { msw: { handlers: [http.get("/api/v1/support/cases/:caseId/compensation-incidents", () => HttpResponse.json({ items: [{ ...incident, benefitIssued: true }], nextCursor: null }))] } }, play: async ({ canvas }) => { await expect(await canvas.findByText("이미 보상한 사고 · 추가 지급 불가")).toBeVisible(); await expect(canvas.getByRole("button", { name: "이 사고 선택" })).toBeDisabled(); } };
