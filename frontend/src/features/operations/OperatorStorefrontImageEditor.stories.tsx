import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent, waitFor } from "storybook/test";
import { HttpResponse, http } from "msw";
import MockDate from "mockdate";
import { ids } from "../../../.storybook/fixtures";
import { OperatorStorefrontImageEditor } from "./OperatorStorefrontImageEditor";
let present = true;
const image = { url: "/demo/catalog/cafe-latte.webp", expiresAt: "2026-10-01T00:15:00Z" };
const handlers = [http.get("/api/v1/operations/stores/:storeId/image", () => HttpResponse.json({ image: present ? image : null })), http.get("/api/v1/operations/stores/:storeId/menus/:menuId/image", () => HttpResponse.json({ image: present ? image : null }))];
const meta = {
  title: "Patterns/Operations/Image editor", component: OperatorStorefrontImageEditor, tags: ["autodocs"], args: { storeId: ids.store, label: "매장 대표 이미지" },
  beforeEach: () => { present = true; MockDate.set("2026-10-01T00:00:00Z"); return () => MockDate.reset(); },
  parameters: { a11y: { test: "error" }, docs: { story: { inline: false, height: "760px" } }, msw: { handlers } },
} satisfies Meta<typeof OperatorStorefrontImageEditor>;
export default meta;
type Story = StoryObj<typeof meta>;
export const Upload: Story = {
  play: async ({ canvas, msw }) => { msw.use(http.put("/api/v1/operations/stores/:storeId/image", async ({ request }) => { expect(request.headers.get("X-Access-Reason")).toBe("STORE_MEDIA_CORRECTION"); expect(request.headers.get("X-BEANFLOW-CSRF")).toBeNull(); expect((await request.formData()).get("image")).toBeInstanceOf(File); return HttpResponse.json(image); })); await userEvent.selectOptions(await canvas.findByLabelText("이미지 작업 사유"), "STORE_MEDIA_CORRECTION"); await userEvent.upload(canvas.getByLabelText("매장 대표 이미지 파일"), new File(["fixture"], "coffee.png", { type: "image/png" })); await waitFor(() => expect(canvas.getByRole("button", { name: "이미지 저장" })).toBeEnabled()); await userEvent.click(canvas.getByRole("button", { name: "이미지 저장" })); await expect(await canvas.findByText("이미지를 저장했습니다.")).toBeVisible(); },
};
export const DeleteMenu: Story = {
  args: { menuId: ids.menu },
  play: async ({ canvas, msw }) => { msw.use(http.delete("/api/v1/operations/stores/:storeId/menus/:menuId/image", ({ request }) => { expect(request.headers.get("X-Access-Reason")).toBe("STORE_MEDIA_REMOVAL"); present = false; return new HttpResponse(null, { status: 204 }); })); await userEvent.selectOptions(await canvas.findByLabelText("이미지 작업 사유"), "STORE_MEDIA_REMOVAL"); await waitFor(() => expect(canvas.getByRole("button", { name: "이미지 삭제" })).toBeEnabled()); await userEvent.click(canvas.getByRole("button", { name: "이미지 삭제" })); await userEvent.click(canvas.getByRole("button", { name: "삭제 확인" })); await expect(await canvas.findByText("이미지를 삭제했습니다.")).toBeVisible(); await expect(await canvas.findByText("등록된 이미지가 없습니다.")).toBeVisible(); await expect(canvas.queryByRole("alert")).not.toBeInTheDocument(); },
};
export const Denied: Story = { parameters: { msw: { handlers: [http.get("/api/v1/operations/stores/:storeId/image", () => HttpResponse.json({ code: "ACCESS_DENIED" }, { status: 403 }))] } }, play: async ({ canvas }) => { await expect(await canvas.findByRole("alert")).toBeVisible(); await expect(canvas.getByRole("button", { name: "이미지 저장" })).toBeDisabled(); await expect(canvas.getByRole("button", { name: "이미지 삭제" })).toBeDisabled(); } };
export const UnknownUpload: Story = { ...Upload, play: async ({ canvas, msw }) => { msw.use(http.put("/api/v1/operations/stores/:storeId/image", () => HttpResponse.error())); await userEvent.upload(await canvas.findByLabelText("매장 대표 이미지 파일"), new File(["fixture"], "coffee.png", { type: "image/png" })); await waitFor(() => expect(canvas.getByRole("button", { name: "이미지 저장" })).toBeEnabled()); await userEvent.click(canvas.getByRole("button", { name: "이미지 저장" })); await expect(await canvas.findByText("이미지 처리 결과를 확인해 주세요")).toBeVisible(); await expect(canvas.queryByText("이미지를 저장했습니다.")).not.toBeInTheDocument(); } };
