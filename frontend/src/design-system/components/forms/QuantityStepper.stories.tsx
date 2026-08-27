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
    await userEvent.click(canvas.getByRole("button", { name: "아메리카노 수량 늘리기" }));
    await expect(canvas.getByRole("status", { name: "아메리카노 수량 2" })).toHaveTextContent("2");
  },
};
export const AtMaximum: Story = { args: { value: 20, label: "아메리카노 수량", onChange: () => undefined } };
