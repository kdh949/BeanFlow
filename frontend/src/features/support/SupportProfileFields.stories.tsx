import { useState } from "react";
import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";
import { ProfileFields } from "./SupportProfileChangeWorkspace";

const meta = {
  title: "Patterns/Support/External business codes",
  component: ProfileFields,
  tags: ["autodocs"],
  args: { purpose: "STORE_SETTLEMENT_ACCOUNT", values: {}, onChange: fn(), disabled: false },
  parameters: { a11y: { test: "error" }, docs: { description: { component: "외부 기관이 발급한 정산·배달 업무 코드의 출처와 형식을 설명합니다. 개인정보 원문 대신 사용하는 업무 코드이며, 승인용 해시와 실행 시 재입력 경계는 유지합니다." } } },
  render: args => {
    const [values, setValues] = useState(args.values);
    return <ProfileFields {...args} values={values} onChange={next => { setValues(next); args.onChange(next); }} />;
  },
} satisfies Meta<typeof ProfileFields>;
export default meta;
type Story = StoryObj<typeof meta>;

export const StoreSettlementCode: Story = { play: async ({ canvas, args }) => {
  const field = canvas.getByLabelText("매장 정산 등록 코드");
  await expect(field).toHaveAccessibleDescription(/정산기관에서 등록을 마친 계정/);
  await userEvent.type(field, "settlement:store-review");
  await expect(args.onChange).toHaveBeenLastCalledWith({ accountReference: "settlement:store-review" });
} };
export const CourierProviderCode: Story = { args: { purpose: "COURIER_PROVIDER_IDENTITY" }, play: async ({ canvas }) => {
  await expect(canvas.getByLabelText("배달업체 배달원 등록 코드")).toHaveAccessibleDescription(/배달업체가 해당 배달원에게 발급한/);
} };
export const CourierPayoutCode: Story = { args: { purpose: "COURIER_PAYOUT_REFERENCE" }, play: async ({ canvas }) => {
  await expect(canvas.getByLabelText("배달원 정산 등록 코드")).toHaveAccessibleDescription(/정산기관에서 등록을 마친 배달원/);
} };
