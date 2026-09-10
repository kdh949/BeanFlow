import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";
import { http, HttpResponse } from "msw";
import { OperationsRecoveryPage } from "./OperationsRecoveryPage";
const meta = { title: "Pages/Operations/Recovery", component: OperationsRecoveryPage, tags: ["autodocs"], parameters: { a11y: { test: "error" }, routing: { path: "/ops/recovery", initialEntry: "/ops/recovery" }, msw: { handlers: [http.get("/api/v1/operations/notification-delivery-recoveries", () => HttpResponse.json({ items: [], nextCursor: null })), http.get("/api/v1/operations/event-publication-recoveries", () => HttpResponse.json({ items: [], nextCursor: null }))] }, docs: { description: { component: "알림과 이벤트 전달의 수동 복구를 구분합니다. 예시 수치나 미연결 정산/감사 탭을 표시하지 않습니다." }, story: { inline: false, height: "850px" } } } } satisfies Meta<typeof OperationsRecoveryPage>;
export default meta; type Story = StoryObj<typeof meta>;
export const RecoveryTabs: Story = { play: async ({ canvas }) => { await expect(await canvas.findByRole("heading", { name: "알림 전달 복구" })).toBeVisible(); await userEvent.click(canvas.getByRole("tab", { name: "이벤트 전달" })); await expect(await canvas.findByRole("heading", { name: "이벤트 전달 복구" })).toBeVisible(); await expect(await canvas.findByText("복구 내역이 없습니다")).toBeVisible(); } };

export const OrderAndRepairTabs: Story = { play: async ({ canvas }) => { await userEvent.click(canvas.getByRole("tab", { name: "주문 후속 처리" })); await expect(await canvas.findByLabelText("후속 처리 주문 ID")).toBeVisible(); await userEvent.click(canvas.getByRole("tab", { name: "환불 복구 승인" })); await expect(await canvas.findByLabelText("복구 제안 ID")).toBeVisible(); } };

export const PointInvestigation: Story = { play: async ({ canvas }) => { await userEvent.click(canvas.getByRole("tab", { name: "포인트 조사" })); await expect(await canvas.findByLabelText("포인트 계정 ID")).toBeVisible(); } };
