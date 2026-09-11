import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent, waitFor } from "storybook/test";
import { HttpResponse, http } from "msw";
import MockDate from "mockdate";
import { ids, merchantSignedInHandlers } from "../../../.storybook/fixtures";
import { StorefrontImageEditor } from "./StorefrontImageEditor";
let present = true;
const image = { url: "/demo/catalog/cafe-latte.webp", expiresAt: "2026-10-01T00:15:00Z" };
const handlers = [...merchantSignedInHandlers,
  http.get("/api/v1/stores/:storeId/image", () => HttpResponse.json({ image: present ? image : null })),
  http.put("/api/v1/stores/:storeId/image", async ({ request }) => { expect((await request.formData()).get("image")).toBeInstanceOf(File); expect(request.headers.get("X-BEANFLOW-CSRF")).toBeTruthy(); present = true; return HttpResponse.json(image); }),
  http.delete("/api/v1/stores/:storeId/image", () => { present = false; return new HttpResponse(null, { status: 204 }); }),
];
const meta = {
  title: "Patterns/Store/Image editor", component: StorefrontImageEditor, tags: ["autodocs"], args: { storeId: ids.store, label: "매장 대표 이미지" },
  beforeEach: () => { present = true; MockDate.set("2026-10-01T00:00:00Z"); return () => MockDate.reset(); },
  parameters: { a11y: { test: "error" }, docs: { description: { component: "권한 있는 현재 이미지 조회, 파일 교체와 명시적 삭제를 같은 흐름으로 처리합니다. 한시 URL은 저장하지 않습니다." }, story: { inline: false, height: "760px" } }, msw: { handlers } },
} satisfies Meta<typeof StorefrontImageEditor>;
export default meta;
type Story = StoryObj<typeof meta>;
export const CurrentImage: Story = { play: async ({ canvas }) => { await expect(await canvas.findByRole("img", { name: "매장 대표 이미지" })).toBeVisible(); } };
export const Upload: Story = { beforeEach: () => { present = false; }, play: async ({ canvas }) => { await userEvent.upload(await canvas.findByLabelText("매장 대표 이미지 파일"), new File(["fixture"], "coffee.png", { type: "image/png" })); await userEvent.click(canvas.getByRole("button", { name: "이미지 저장" })); await expect(await canvas.findByText("이미지를 저장했습니다.")).toBeVisible(); await expect(await canvas.findByRole("img")).toBeVisible(); } };
export const Delete: Story = { play: async ({ canvas }) => { await waitFor(() => expect(canvas.getByRole("button", { name: "이미지 삭제" })).toBeEnabled()); await userEvent.click(canvas.getByRole("button", { name: "이미지 삭제" })); await userEvent.click(canvas.getByRole("button", { name: "삭제 확인" })); await expect(await canvas.findByText("등록된 이미지가 없습니다.")).toBeVisible(); await expect(canvas.queryByRole("img")).not.toBeInTheDocument(); await expect(canvas.getByText("이미지를 삭제했습니다.")).toBeVisible(); await expect(canvas.queryByRole("alert")).not.toBeInTheDocument(); await expect(canvas.queryByRole("button", { name: "삭제 확인" })).not.toBeInTheDocument(); } };
export const MenuDelete: Story = { ...Delete, args: { menuId: ids.menu }, parameters: { msw: { handlers: [http.get("/api/v1/stores/:storeId/menus/:menuId/image", () => HttpResponse.json({ image: present ? image : null })), http.delete("/api/v1/stores/:storeId/menus/:menuId/image", () => { present = false; return new HttpResponse(null, { status: 204 }); }), ...handlers] } } };
export const ExpiredLease: Story = { parameters: { msw: { handlers: [http.get("/api/v1/stores/:storeId/image", () => HttpResponse.json({ image: { ...image, expiresAt: "2026-09-01T00:00:00Z" } })), ...handlers] } }, play: async ({ canvas }) => { await expect(await canvas.findByText("이미지를 다시 확인해 주세요")).toBeVisible(); await expect(canvas.queryByRole("img")).not.toBeInTheDocument(); } };
export const FailedUpload: Story = { parameters: { msw: { handlers: [http.put("/api/v1/stores/:storeId/image", () => HttpResponse.json({ code: "DEPENDENCY_UNAVAILABLE", message: "unavailable" }, { status: 503 })), ...handlers] } }, play: async ({ canvas }) => { await userEvent.upload(await canvas.findByLabelText("매장 대표 이미지 파일"), new File(["fixture"], "coffee.png", { type: "image/png" })); await userEvent.click(canvas.getByRole("button", { name: "이미지 저장" })); await expect(await canvas.findByRole("alert")).toBeVisible(); await expect(canvas.queryByText("이미지를 저장했습니다.")).not.toBeInTheDocument(); } };
export const InvalidFile: Story = { play: async ({ canvas }) => { await userEvent.upload(await canvas.findByLabelText("매장 대표 이미지 파일"), new File([new Uint8Array(5 * 1024 * 1024 + 1)], "large.png", { type: "image/png" })); await waitFor(() => expect(canvas.getByRole("button", { name: "이미지 저장" })).toBeDisabled()); await expect(canvas.getByText("JPEG 또는 PNG, 최대 5 MiB 파일을 선택해 주세요.")).toBeVisible(); } };
