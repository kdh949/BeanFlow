import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent, waitFor } from "storybook/test";
import { HttpResponse, http } from "msw";
import { ids, merchantSignedInHandlers } from "../../../.storybook/fixtures";
import { StoreDisputesPage } from "./StoreDisputesPage";

const ownerStores = http.get("/api/v1/merchant/me/stores", () =>
  HttpResponse.json([{ storeId: ids.store, storeName: "시청점", membershipRole: "OWNER" }]));

const filed = {
  disputeId: "92000000-0000-4000-8000-000000000001",
  settlementItemId: "91000000-0000-4000-8000-000000000001",
  state: "FILED",
  expectedAdjustmentKrw: 3_500,
  heldAmountKrw: 3_500,
  filedAt: "2026-08-16T10:00:00+09:00",
};

const accepted = {
  disputeId: "92000000-0000-4000-8000-000000000002",
  settlementItemId: "91000000-0000-4000-8000-000000000002",
  state: "ACCEPTED",
  expectedAdjustmentKrw: 1_200,
  heldAmountKrw: 0,
  filedAt: "2026-08-15T09:00:00+09:00",
  decidedAt: "2026-08-16T09:00:00+09:00",
};

/** The list is state-filtered on the server; the filter is part of the signed cursor scope. */
const disputeHandler = http.get("/api/v1/stores/:storeId/disputes", ({ request }) => {
  const state = new URL(request.url).searchParams.get("state");
  const items = [filed, accepted].filter((dispute) => !state || dispute.state === state);
  return HttpResponse.json({ items, page: {} });
});

const meta = {
  title: "Pages/Store/Disputes",
  component: StoreDisputesPage,
  tags: ["autodocs"],
  parameters: {
    docs: {
      description: {
        component:
          "접수한 이의제기의 상태와 보류 금액을 보는 점주 전용 목록입니다. 사유·증빙·접수자 같은 내부 filing 증거는 담지 않습니다.",
      },
      story: { inline: false, height: "700px" },
    },
    routing: { surface: "store", path: "/store/disputes", initialEntry: "/store/disputes" },
    msw: { handlers: [...merchantSignedInHandlers, ownerStores, disputeHandler] },
  },
} satisfies Meta<typeof StoreDisputesPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const FiledAndDecided: Story = {
  play: async ({ canvas }) => {
    await expect(await canvas.findAllByText("₩3,500")).toHaveLength(2);
    await expect(canvas.getByText("진행 중")).toBeVisible();
  },
};

export const FilteredByState: Story = {
  play: async ({ canvas }) => {
    await userEvent.click(await canvas.findByRole("button", { name: "인정" }));
    await expect(await canvas.findByText("₩1,200")).toBeVisible();
    await expect(canvas.queryAllByText("₩3,500")).toHaveLength(0);
  },
};

export const NothingFiledYet: Story = {
  parameters: {
    msw: {
      handlers: [
        ...merchantSignedInHandlers,
        ownerStores,
        http.get("/api/v1/stores/:storeId/disputes", () => HttpResponse.json({ items: [], page: {} })),
      ],
    },
  },
  play: async ({ canvas }) => {
    await waitFor(() => {
      const visibleEmptyStates = canvas.getAllByText("접수한 이의제기가 없습니다").filter((element) => element.checkVisibility());
      expect(visibleEmptyStates).toHaveLength(1);
    });
  },
};

/** A query failure is retryable, never "no disputes were filed". */
export const DisputeQueryUnavailable: Story = {
  parameters: {
    msw: {
      handlers: [
        ...merchantSignedInHandlers,
        ownerStores,
        http.get("/api/v1/stores/:storeId/disputes", () =>
          HttpResponse.json(
            { code: "DEPENDENCY_UNAVAILABLE", message: "이의제기 조회를 완료하지 못했습니다.", correlationId: "REQ-DEMO-42" },
            { status: 503 },
          )),
      ],
    },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText("서비스 연결을 확인하고 있습니다")).toBeVisible();
  },
};
