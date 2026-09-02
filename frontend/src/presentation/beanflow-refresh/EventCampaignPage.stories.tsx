import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";
import { HttpResponse, http } from "msw";
import { EventCampaignPage } from "./EventCampaignPage";

const campaign = (index: number, title: string, color: string, accent: string) => ({
  campaignId: `8a8999bf-3432-4a5d-b599-43bbc3ddc2e${index}`,
  store: { storeId: `5273704d-f924-59e0-8883-827535fb86a${index}`, name: ["빈플로우 성수", "빈플로우 잠실", "빈플로우 여의도"][index] },
  title,
  summary: ["라떼 한 잔에 1,000원 할인", "디저트와 함께 15% 할인", "콜드브루 신메뉴 2,000원 할인"][index],
  bannerAltText: `${title}를 알리는 컬러 이벤트 배너`,
  banner: { url: bannerData(color, accent), expiresAt: "2026-09-02T21:15:00+09:00" },
  benefit: index === 1
    ? { discountType: "RATE_BPS", fixedAmountKrw: null, rateBps: 1500, maximumDiscountKrw: 5000 }
    : { discountType: "FIXED_KRW", fixedAmountKrw: index === 0 ? 1000 : 2000, rateBps: null, maximumDiscountKrw: null },
  minimumOrderKrw: 5000,
  remainingCount: [93, 42, 18][index],
  claimEndsAt: [`2026-09-06T23:59:59+09:00`, `2026-09-08T23:59:59+09:00`, `2026-09-10T23:59:59+09:00`][index],
  couponExpiresAt: "2026-09-30T23:59:59+09:00",
  claimed: false,
});

const events = [
  campaign(0, "가을 라떼 선착순 쿠폰", "#ff7a1a", "#ffbf3f"),
  campaign(1, "디저트 페어링 위크", "#057a55", "#b8d83d"),
  campaign(2, "콜드브루 신메뉴 혜택", "#7652c8", "#ff8d5b"),
];

const meta = {
  title: "Pages/Customer/Event campaigns",
  component: EventCampaignPage,
  tags: ["autodocs"],
  parameters: {
    docs: { description: { component: "게시 중이고 다운로드 가능한 선착순 쿠폰 배너를 고객 앱 프레임 안에 세로로 보여주는 이벤트 화면입니다." }, story: { inline: false, height: "920px" } },
    routing: { path: "/app/events", initialEntry: "/app/events", surface: "refresh-customer" },
    msw: { handlers: [
      http.get("/api/v1/me/events", () => HttpResponse.json(events)),
      http.post("/api/v1/me/events/:campaignId/claims", async ({ params, request }) => {
        if (!request.headers.get("Idempotency-Key") || !request.headers.get("X-BEANFLOW-CSRF")) {
          return HttpResponse.json({ code: "INVALID_REQUEST", message: "required headers missing" }, { status: 400 });
        }
        return HttpResponse.json({ campaignId: params.campaignId, couponIssuanceId: "ce192922-5a03-4f85-84d4-a46330243670", claimedAt: "2026-09-02T19:47:00+09:00", couponExpiresAt: "2026-09-30T23:59:59+09:00" }, { status: 201 });
      }),
    ] },
  },
} satisfies Meta<typeof EventCampaignPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const ActiveEvents: Story = {
  play: async ({ canvas }) => {
    document.cookie = "BEANFLOW_CUSTOMER_XSRF=storybook-csrf; path=/";
    await expect(await canvas.findByRole("heading", { name: "가을 라떼 선착순 쿠폰" })).toBeVisible();
    await expect(canvas.getAllByRole("article")).toHaveLength(3);
    await expect(canvas.getByText("~ 09.06까지")).toBeVisible();
    await expect(canvas.getByText("선착순 93명")).toBeVisible();
    await userEvent.click(canvas.getAllByRole("button", { name: "쿠폰 받기" })[0]!);
    await expect(await canvas.findByText("쿠폰을 받았어요. 쿠폰함에서 바로 확인할 수 있어요.")).toBeVisible();
    await expect(canvas.getByRole("link", { name: "쿠폰함 보기" })).toHaveAttribute("href", `/app/coupons?storeId=${events[0]!.store.storeId}`);
  },
};

export const AlreadyClaimed: Story = {
  parameters: { msw: { handlers: [http.get("/api/v1/me/events", () => HttpResponse.json([{ ...events[0], claimed: true }]))] } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("link", { name: "쿠폰함 보기" })).toBeVisible();
    await expect(canvas.queryByRole("button", { name: "쿠폰 받기" })).not.toBeInTheDocument();
  },
};

export const SoldOutDuringClaim: Story = {
  parameters: { msw: { handlers: [
    http.get("/api/v1/me/events", () => HttpResponse.json([events[0]])),
    http.post("/api/v1/me/events/:campaignId/claims", () => HttpResponse.json({ code: "CAMPAIGN_QUOTA_EXHAUSTED", message: "quota exhausted" }, { status: 409 })),
  ] } },
  play: async ({ canvas }) => {
    document.cookie = "BEANFLOW_CUSTOMER_XSRF=storybook-csrf; path=/";
    await userEvent.click(await canvas.findByRole("button", { name: "쿠폰 받기" }));
    await expect(await canvas.findByText("방금 쿠폰이 모두 소진됐어요.")).toBeVisible();
  },
};

export const EmptyEvents: Story = {
  parameters: { msw: { handlers: [http.get("/api/v1/me/events", () => HttpResponse.json([]))] } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("진행 중인 이벤트가 없어요")).toBeVisible();
  },
};

export const Unavailable: Story = {
  parameters: { msw: { handlers: [http.get("/api/v1/me/events", () => HttpResponse.json({ code: "DEPENDENCY_UNAVAILABLE", message: "events unavailable" }, { status: 503 }))] } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole("alert")).toHaveTextContent("서비스 연결을 확인하고 있습니다");
  },
};

function bannerData(color: string, accent: string) {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1200 450"><defs><linearGradient id="g" x2="1" y2="1"><stop stop-color="${color}"/><stop offset="1" stop-color="${accent}"/></linearGradient></defs><rect width="1200" height="450" rx="36" fill="url(#g)"/><circle cx="980" cy="210" r="170" fill="white" opacity=".22"/><circle cx="1050" cy="110" r="54" fill="white" opacity=".3"/><path d="M835 145h190v155c0 48-39 87-87 87h-16c-48 0-87-39-87-87z" fill="white" opacity=".9"/><path d="M1025 190h38c61 0 61 90 0 90h-38" fill="none" stroke="white" stroke-width="28" opacity=".9"/><path d="M895 90c-35 42 24 55-6 96M955 78c-35 42 24 55-6 96" fill="none" stroke="white" stroke-width="16" stroke-linecap="round" opacity=".75"/></svg>`;
  return `data:image/svg+xml,${encodeURIComponent(svg)}`;
}
