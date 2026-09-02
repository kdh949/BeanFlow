import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent, within } from "storybook/test";
import { HttpResponse, http } from "msw";
import { CouponCampaignsPage } from "./CouponCampaignsPage";

const campaign = {
  campaignId: "8a8999bf-3432-4a5d-b599-43bbc3ddc2e9",
  store: { storeId: "5273704d-f924-59e0-8883-827535fb86ad", name: "빈플로우 성수" },
  state: "DRAFT",
  "title": "가을 라떼 선착순 쿠폰",
  summary: "선착순 100명에게 라떼 1,000원 할인",
  bannerAltText: "노란 배경의 가을 라떼 쿠폰 배너",
  banner: null,
  discount: { discountType: "FIXED_KRW", fixedAmountKrw: 1000, rateBps: null, maximumDiscountKrw: null },
  minimumOrderKrw: 5000,
  allMenusEligible: true,
  eligibleMenuIds: [],
  cost: { costBearer: "PLATFORM", platformShareBps: 10000, storeShareBps: 0 },
  totalQuota: 100,
  issuedCount: 0,
  claimStartsAt: "2026-10-01T00:00:00+09:00",
  claimEndsAt: "2026-10-10T23:59:59+09:00",
  couponExpiresAt: "2026-10-31T23:59:59+09:00",
  createdAt: "2026-09-02T20:00:00+09:00",
  updatedAt: "2026-09-02T20:00:00+09:00",
  version: 0,
};

const listCampaigns = http.get("/api/v1/operations/coupon-campaigns", () => HttpResponse.json({ items: [campaign], page: { nextCursor: null } }));
const createCampaign = http.post("/api/v1/operations/coupon-campaigns", () => HttpResponse.json(campaign, { status: 201 }));
const listStores = http.get("/api/v1/operations/coupon-campaigns/store-options", () => HttpResponse.json([campaign.store]));
const listMenus = http.get("/api/v1/operations/coupon-campaigns/store-options/:storeId/menus", () => HttpResponse.json([{ menuId: "7419fd51-d17d-43c0-bc16-0d9496c90d97", name: "시그니처 라떼", basePriceKrw: 5800 }]));
const uploadedCampaign = { ...campaign, banner: { url: "https://images.example.test/campaign.jpg", expiresAt: "2026-09-02T21:15:00+09:00" }, version: 1 };
const replaceBanner = http.put(/\/api\/v1\/operations\/coupon-campaigns\/[^/]+\/banner(?:\?.*)?$/, () => HttpResponse.json(uploadedCampaign));
const publishCampaign = http.post(/\/api\/v1\/operations\/coupon-campaigns\/[^/]+\/publication$/, () => HttpResponse.json({ ...uploadedCampaign, state: "PUBLISHED", version: 2 }));

const meta = {
  title: "Pages/Operations/Coupon campaigns",
  component: CouponCampaignsPage,
  tags: ["autodocs"],
  parameters: {
    docs: { description: { component: "운영팀이 선착순 쿠폰의 전체 조건과 고정 만료 시각을 초안으로 만들고 상태를 확인하는 화면입니다." }, story: { inline: false, height: "980px" } },
    routing: { path: "/ops/campaigns", initialEntry: "/ops/campaigns" },
    msw: { handlers: [listCampaigns, createCampaign, listStores, listMenus, replaceBanner, publishCampaign] },
  },
} satisfies Meta<typeof CouponCampaignsPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const CampaignList: Story = {
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("가을 라떼 선착순 쿠폰")).toBeVisible();
    await expect(canvas.getByText("0 / 100")).toBeVisible();
  },
};

export const CompleteDraftForm: Story = {
  play: async ({ canvas }) => {
    await userEvent.click(await canvas.findByRole("button", { name: "새 캠페인" }));
    await expect(canvas.getByRole("heading", { name: "캠페인 기본 설정" })).toBeVisible();
    await canvas.findByRole("option", { name: "빈플로우 성수" });
    await userEvent.selectOptions(canvas.getByLabelText("매장"), campaign.store.storeId);
    await userEvent.type(canvas.getByLabelText("캠페인 제목"), "가을 라떼 선착순 쿠폰");
    await expect(canvas.getByLabelText("쿠폰 만료 시각")).toBeVisible();
  },
};

export const BannerPublication: Story = {
  play: async ({ canvas }) => {
    const input = await canvas.findByLabelText("이벤트 배너");
    await userEvent.upload(input, new File(["banner"], "campaign.png", { type: "image/png" }));
    await userEvent.type(canvas.getByLabelText("변경 사유"), "배너와 혜택 조건 검수 완료");
    await userEvent.click(canvas.getByRole("button", { name: "배너 업로드" }));
    await expect(await canvas.findByAltText("노란 배경의 가을 라떼 쿠폰 배너")).toBeVisible();
    await userEvent.click(canvas.getByRole("button", { name: "고객에게 게시" }));
    await expect(await canvas.findByText("PUBLISHED")).toBeVisible();
  },
};

export const EmptyCampaigns: Story = {
  parameters: { msw: { handlers: [http.get("/api/v1/operations/coupon-campaigns", () => HttpResponse.json({ items: [], page: { nextCursor: null } })), listStores] } },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(await canvas.findByText("등록된 캠페인이 없습니다")).toBeVisible();
  },
};
