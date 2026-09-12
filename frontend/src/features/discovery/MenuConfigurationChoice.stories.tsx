import type { Meta, StoryObj } from "@storybook/react-vite";
import { useState } from "react";
import { expect, fn, userEvent } from "storybook/test";
import { HttpResponse, http } from "msw";
import { ids } from "../../../.storybook/fixtures";
import { MenuConfigurationChoice } from "./MenuConfigurationChoice";

const meta = {
  title: "Patterns/Customer/Menu configuration choice",
  component: MenuConfigurationChoice,
  tags: ["autodocs"],
  parameters: { a11y: { test: "error" }, docs: { description: { component: "Merchant가 등록한 현재 구성 하나를 선택합니다. 조회 실패·구성 없음·품절을 구분하며 가격과 주문 가능성은 최종 견적에서 다시 확인합니다." }, story: { inline: false, height: "480px" } }, msw: { handlers: [http.get("/api/v1/stores/:storeId/menus/:menuId/configurations", () => HttpResponse.json({ items: [{ configurationId: "basic", optionIds: [], available: true }, { configurationId: "shot", optionIds: ["shot"], available: true }] }))] } },
  args: { storeId: ids.store, menu: { menuId: ids.menu, name: "라떼", basePriceKrw: 5000, currency: "KRW", available: true, options: [{ optionId: "shot", name: "샷 추가", available: true, additionalPriceKrw: 500 }] }, optionIds: [], onChange: fn(), onValidityChange: fn() },
  render: function Controlled(args) {
    const [optionIds, setOptionIds] = useState(args.optionIds);
    return <MenuConfigurationChoice {...args} optionIds={optionIds} onChange={(value) => { setOptionIds(value); args.onChange(value); }} />;
  },
} satisfies Meta<typeof MenuConfigurationChoice>;
export default meta;
type Story = StoryObj<typeof meta>;

export const RegisteredChoice: Story = {
  play: async ({ canvas, args }) => {
    await userEvent.click(await canvas.findByRole("radio", { name: /샷 추가/ }));
    await expect(args.onChange).toHaveBeenCalledWith(["shot"]);
    await expect(args.onValidityChange).toHaveBeenLastCalledWith(true);
  },
};

export const NoConfigurations: Story = {
  parameters: { msw: { handlers: [http.get("/api/v1/stores/:storeId/menus/:menuId/configurations", () => HttpResponse.json({ items: [] }))] } },
  play: async ({ canvas, args }) => {
    await expect(await canvas.findByText("판매 중인 구성이 없어요")).toBeVisible();
    await expect(args.onValidityChange).toHaveBeenLastCalledWith(false);
    await expect(canvas.queryByRole("radio")).not.toBeInTheDocument();
  },
};
