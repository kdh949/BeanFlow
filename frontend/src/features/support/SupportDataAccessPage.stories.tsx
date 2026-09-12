import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
import { http, HttpResponse } from "msw";
import { SupportDataAccessPage } from "./SupportDataAccessWorkspace";
const grantId = "a6000000-0000-4000-8000-000000000001";
const caseId = "a1000000-0000-4000-8000-000000000001";
const meta = { title: "Pages/Support/Data access review", component: SupportDataAccessPage, tags: ["autodocs"], parameters: { a11y: { test: "error" }, routing: { path: "/support/data-access/:grantId", initialEntry: `/support/data-access/${grantId}` }, msw: { handlers: [http.get("/api/v1/support/data-access-grants/:grantId", () => HttpResponse.json({ viewerRole: "APPROVER", grant: { grantId, caseId, subjectLinkId: caseId, subjectType: "CUSTOMER", subjectId: caseId, purpose: "CONTACT_CONFIRMATION", fields: ["CUSTOMER_PRIMARY_PHONE"], risk: "SENSITIVE", state: "APPROVAL_PENDING", maxReveals: 1, reservedReveals: 0, requestedAt: "2026-09-10T09:00:00Z", expiresAt: null, version: 1 } }))] }, docs: { story: { inline: false, height: "1000px" } } } } satisfies Meta<typeof SupportDataAccessPage>;
export default meta; type Story = StoryObj<typeof meta>;
export const ReviewFromLink: Story = { play: async ({ canvas }) => { await expect(await canvas.findByRole("heading", { name: "개인정보 열람 요청 검토" })).toBeVisible(); await expect(await canvas.findByRole("button", { name: "열람 승인" })).toBeVisible(); await expect(canvas.getByRole("link", { name: "상담 건 열기" })).toHaveAttribute("href", `/support/cases/${caseId}`); } };
