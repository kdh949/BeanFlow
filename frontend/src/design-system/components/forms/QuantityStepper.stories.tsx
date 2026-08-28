import { useState } from "react";
import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";
import { QuantityStepper } from "./QuantityStepper";

const meta = {
  title: "Components/Forms/QuantityStepper",
  component: QuantityStepper,
  tags: ["autodocs"],
  parameters: { a11y: { test: "error" } },
  render: (args) => {
    const [value, setValue] = useState(args.value);
    return <QuantityStepper {...args} value={value} onChange={setValue} />;
  },
} satisfies Meta<typeof QuantityStepper>;

export default meta;
type Story = StoryObj<typeof meta>;
export const Default: Story = {
  args: { value: 1, label: "아메리카노 수량", onChange: () => undefined },
  play: async ({ canvas }) => {
    const decrease = canvas.getByRole("button", { name: "아메리카노 수량 줄이기" });
    const increase = canvas.getByRole("button", { name: "아메리카노 수량 늘리기" });
    for (const control of [decrease, increase]) {
      const bounds = control.getBoundingClientRect();
      await expect(bounds.width).toBeGreaterThanOrEqual(44);
      await expect(bounds.height).toBeGreaterThanOrEqual(44);
    }
    await userEvent.click(increase);
    await expect(canvas.getByRole("status", { name: "아메리카노 수량 2" })).toHaveTextContent("2");
  },
};
export const AtMaximum: Story = { args: { value: 20, label: "아메리카노 수량", onChange: () => undefined } };
export const Disabled: Story = {
  args: { value: 2, label: "아메리카노 수량", onChange: () => undefined, disabled: true },
  play: async ({ canvas }) => {
    await expect(canvas.getByRole("button", { name: "아메리카노 수량 줄이기" })).toBeDisabled();
    await expect(canvas.getByRole("button", { name: "아메리카노 수량 늘리기" })).toBeDisabled();
  },
};
