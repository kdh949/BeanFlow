import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";
import { delay, http, HttpResponse } from "msw";
import { SupportOrderConsentPicker } from "./SupportOrderConsentPicker";

const id = "a1000000-0000-4000-8000-000000000001";
const consent = { authorizationId: id, authorizationType: "CONFIRMATION", authorizedAt: "2099-09-11T09:00:00Z", expiresAt: "2099-09-11T09:15:00Z", remainingUses: 1 };
const handler = http.get("/api/v1/support/action-requests/:requestId/store-consents", () => HttpResponse.json({ items: [consent], nextCursor: null }));
const meta = { title: "Patterns/Support/Order consent selection", component: SupportOrderConsentPicker, tags: ["autodocs"], args: { requestId: id, value: null, onValueChange: fn(), disabled: false }, parameters: { docs: { story: { inline: false, height: "700px" } }, a11y: { test: "error" }, msw: { handlers: [handler] } } } satisfies Meta<typeof SupportOrderConsentPicker>;
export default meta;
type Story = StoryObj<typeof meta>;
export const SelectConsent: Story = { play: async ({ canvas, args }) => { await userEvent.click(await canvas.findByRole("button", { name: "이 동의 선택" })); await expect(args.onValueChange).toHaveBeenCalledWith(consent); } };
export const Empty: Story = { parameters: { msw: { handlers: [http.get("/api/v1/support/action-requests/:requestId/store-consents", () => HttpResponse.json({ items: [], nextCursor: null }))] } }, play: async ({ canvas }) => { await expect(await canvas.findByText("사용할 수 있는 매장 동의가 없습니다")).toBeVisible(); } };
export const Unavailable: Story = { parameters: { msw: { handlers: [http.get("/api/v1/support/action-requests/:requestId/store-consents", () => HttpResponse.json({ code: "DEPENDENCY_UNAVAILABLE" }, { status: 503 }))] } }, play: async ({ canvas }) => { await expect(await canvas.findByRole("alert")).toBeVisible(); await expect(canvas.queryByText("사용할 수 있는 매장 동의가 없습니다")).not.toBeInTheDocument(); } };
export const Loading: Story = { parameters: { msw: { handlers: [http.get("/api/v1/support/action-requests/:requestId/store-consents", async () => { await delay("infinite"); return HttpResponse.json({ items: [] }); })] } }, play: async ({ canvas }) => { await expect(await canvas.findByText("유효한 매장 동의를 확인하는 중")).toBeVisible(); } };
export const Locked: Story = { args: { disabled: true }, play: async ({ canvas }) => { await expect(await canvas.findByRole("button", { name: "이 동의 선택" })).toBeDisabled(); } };
export const Expired: Story = { args: { value: { ...consent, authorizationType: "CONFIRMATION", expiresAt: "2000-01-01T00:00:00Z" } }, play: async ({ canvas }) => { await expect(await canvas.findByText("선택한 매장 동의가 만료되었습니다")).toBeVisible(); } };
