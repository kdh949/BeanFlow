import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";
import { FileField } from "./Field";

const meta = {
  title: "Components/Forms/FileField",
  component: FileField,
  tags: ["autodocs"],
  parameters: { a11y: { test: "error" } },
  args: {
    label: "이벤트 배너",
    description: "JPEG 또는 PNG, 최대 5MiB",
    accept: "image/jpeg,image/png",
    onFileChange: fn(),
  },
} satisfies Meta<typeof FileField>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
  play: async ({ args, canvas }) => {
    const input = canvas.getByLabelText("이벤트 배너");
    const file = new File(["banner"], "event-banner.png", { type: "image/png" });
    await userEvent.upload(input, file);
    await expect(input).toHaveAccessibleDescription("JPEG 또는 PNG, 최대 5MiB");
    await expect(args.onFileChange).toHaveBeenCalledWith(file);
  },
};

export const Invalid: Story = {
  args: { error: "JPEG 또는 PNG 이미지를 선택해 주세요." },
  play: async ({ canvas }) => {
    await expect(canvas.getByLabelText("이벤트 배너")).toHaveAttribute("aria-invalid", "true");
  },
};
