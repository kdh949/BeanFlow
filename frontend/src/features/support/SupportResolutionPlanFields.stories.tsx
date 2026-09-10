import { useState } from "react";
import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";
import { SupportResolutionPlanFields } from "./SupportResolutionPlanFields";
import { initialResolutionDraft } from "../../lib/supportResolutionPayload";
const meta = { title: "Patterns/Support/Resolution plan", component: SupportResolutionPlanFields, tags: ["autodocs"], args: { value: initialResolutionDraft, onChange: () => undefined, disabled: false }, render: args => { const [value, onChange] = useState(args.value); return <SupportResolutionPlanFields {...args} value={value} onChange={onChange} />; }, parameters: { a11y: { test: "error" }, docs: { description: { component: "수락 후 해결을 위한 환불·혜택 복원·비용 책임과 정산 조정 필드입니다. 선택한 해결 방식에 해당하는 값만 제출하고 증빙 참조를 승인 요청과 대조합니다." } } } } satisfies Meta<typeof SupportResolutionPlanFields>;
export default meta; type Story = StoryObj<typeof meta>;
export const StoreResponsibility: Story = { play: async ({ canvas }) => { await userEvent.selectOptions(canvas.getByLabelText("비용 책임"), "STORE"); await expect(canvas.getByLabelText("매장 정산 조정 금액")).toBeVisible(); await userEvent.click(canvas.getByRole("checkbox", { name: "사용 포인트 복원" })); await userEvent.selectOptions(canvas.getByLabelText("해결 방식"), "NO_MONETARY_RESOLUTION"); await expect(canvas.queryByLabelText("현금 환불 금액")).not.toBeInTheDocument(); await expect(canvas.queryByRole("checkbox")).not.toBeInTheDocument(); } };
