import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
import { SelectField } from "../../components/forms/Field";
import { EventTimeline, FilterToolbar, MetricStrip, WorkflowStepper, WorkspaceDataTable } from "./DenseWorkspacePatterns";

function DenseWorkspaceCatalog() {
  return (
    <main className="bf-dense-workspace-story">
      <MetricStrip items={[{ id: "mine", label: "담당 상담", value: 24, hint: "종료 전 전체", tone: "accent" }, { id: "urgent", label: "긴급", value: 3, hint: "우선 확인", tone: "warning" }]} />
      <FilterToolbar><SelectField label="상태" value="OPEN" onValueChange={() => undefined}><option value="OPEN">새 상담</option></SelectField></FilterToolbar>
      <WorkspaceDataTable caption="패턴 예시" columns={[{ key: "case", label: "상담 건" }, { key: "state", label: "상태" }, { key: "updated", label: "갱신", align: "end" }]} rows={[{ id: "case-1", cells: { case: "S-240520-0055", state: "처리 중", updated: "14:28" } }]} />
      <WorkflowStepper steps={[{ id: "received", label: "요청 접수", state: "complete" }, { id: "review", label: "검토", state: "current" }, { id: "done", label: "완료", state: "upcoming" }]} />
      <EventTimeline items={[{ id: "1", "title": "요청 접수", occurredAt: "2026.08.29 14:28", description: "개인정보 없는 감사 이벤트", tone: "accent" }]} />
    </main>
  );
}

const meta = {
  title: "Patterns/Workspace/Dense data patterns",
  component: DenseWorkspaceCatalog,
  tags: ["autodocs"],
  parameters: { layout: "fullscreen", a11y: { test: "error" }, docs: { story: { inline: false, height: "760px" } } },
} satisfies Meta<typeof DenseWorkspaceCatalog>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Catalog: Story = {
  play: async ({ canvas }) => {
    await expect(canvas.getByRole("table", { name: "패턴 예시" })).toBeVisible();
    await expect(canvas.getByRole("list", { name: "처리 단계" })).toBeVisible();
    await expect(canvas.getByRole("list", { name: "처리 이력" })).toBeVisible();
  },
};

export const EmptyTable: Story = {
  render: () => <main className="bf-dense-workspace-story is-compact"><WorkspaceDataTable caption="빈 목록" columns={[{ key: "case", label: "상담 건" }]} rows={[]} empty="조건에 맞는 상담이 없습니다." /></main>,
};
