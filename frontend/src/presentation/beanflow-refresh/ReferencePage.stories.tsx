import type { Meta, StoryObj } from "@storybook/react-vite";
import { Button, ButtonLink } from "../../design-system";
import { CustomerReferencePage, ReferenceSection, WorkspaceReferencePage } from "./ReferencePage";

const meta = {
  title: "Patterns/Layout/Reference pages",
  component: CustomerReferencePage,
  subcomponents: { ReferenceSection, WorkspaceReferencePage },
  tags: ["autodocs"],
  args: { children: null },
  parameters: {
    a11y: { test: "error" },
    docs: {
      description: {
        component: "18개 reference 화면이 공유하는 content geometry입니다. Customer와 workspace content를 분리하며 shell, API 상태 또는 fixture를 소유하지 않습니다.",
      },
    },
  },
} satisfies Meta<typeof CustomerReferencePage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const CustomerResult: Story = {
  render: () => (
    <CustomerReferencePage title="결제 결과" layout="result">
      <ReferenceSection title="결제가 완료됐습니다">
        <p>주문이 정상적으로 접수되었습니다.</p>
        <ButtonLink to="/app/orders" block>주문 내역 보기</ButtonLink>
      </ReferenceSection>
    </CustomerReferencePage>
  ),
};

export const CustomerList: Story = {
  render: () => (
    <CustomerReferencePage title="주문 내역" description="거래 상태를 확인하세요." layout="list">
      <ReferenceSection title="진행 중"><p>A-142 · 결제 승인</p></ReferenceSection>
    </CustomerReferencePage>
  ),
};

export const WorkspaceContent: Story = {
  render: () => (
    <WorkspaceReferencePage title="정산 내역" description="확정된 정산 배치를 조회합니다." action={<Button variant="secondary">새로고침</Button>}>
      <ReferenceSection title="정산 배치"><p>content slot은 workspace chrome을 렌더링하지 않습니다.</p></ReferenceSection>
    </WorkspaceReferencePage>
  ),
};
