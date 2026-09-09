import { useState } from "react";
import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";
import { TextAreaField } from "./Field";

const meta = { title: "Components/Forms/TextAreaField", component: TextAreaField, tags: ["autodocs"], parameters: { a11y: { test: "error" } }, args: { label: "변경 사유", value: "", maxLength: 500, onValueChange: fn() }, render: (args) => { const [value, setValue] = useState(args.value); return <TextAreaField {...args} value={value} onValueChange={(next) => { args.onValueChange(next); setValue(next); }} />; } } satisfies Meta<typeof TextAreaField>;
export default meta;
type Story = StoryObj<typeof meta>;
export const Default: Story = { play: async ({ canvas }) => { const field = canvas.getByRole("textbox", { name: "변경 사유" }); await userEvent.type(field, "고객 요청을 확인했습니다."); await expect(field).toHaveValue("고객 요청을 확인했습니다."); } };
export const InvalidLongKorean: Story = { args: { value: "", error: "감사 기록을 위해 변경 사유를 입력해 주세요.", description: "내부 구현 예외나 고객 개인정보는 입력하지 않습니다." } };
export const FixedSize: Story = { args: { value: "읽기 쉬운 두 줄 설명", resize: "none", rows: 3 } };
