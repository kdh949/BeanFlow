import type { Meta, StoryObj } from "@storybook/react-vite";
import { Bell, ClipboardList, Settings2 } from "lucide-react";
import { BrandLockup } from "../../components/brand/BrandLockup";
import { WorkspaceFrame } from "./WorkspaceFrame";
import "./WorkspaceFrame.stories.css";

function StorySidebar() {
  return (
    <aside className="bf-workspace-story-sidebar" aria-label="예시 워크스페이스 사이드바">
      <BrandLockup />
      <nav aria-label="예시 워크스페이스 메뉴">
        <a href="#workspace-story-content"><ClipboardList size={18} aria-hidden="true" /><span>대기열</span></a>
        <a href="#workspace-story-content"><Settings2 size={18} aria-hidden="true" /><span>설정</span></a>
      </nav>
    </aside>
  );
}

function StoryTopbar() {
  return (
    <header className="bf-workspace-story-topbar">
      <Bell size={19} aria-hidden="true" />
      <strong>워크스페이스</strong>
    </header>
  );
}

const meta = {
  title: "Patterns/Navigation/Workspace frame",
  component: WorkspaceFrame,
  tags: ["autodocs"],
  args: {
    sidebar: <StorySidebar />,
    topbar: <StoryTopbar />,
    contentId: "workspace-story-content",
    children: <section className="bf-workspace-story-content"><h1>업무 화면</h1><p>각 surface는 이 content slot에 route 화면만 제공합니다.</p></section>,
  },
  parameters: {
    layout: "fullscreen",
    a11y: { test: "error" },
    docs: {
      description: {
        component: "Store와 Support가 공유하는 application-neutral geometry입니다. 메뉴, actor, route와 session 의미는 각 surface shell이 소유합니다.",
      },
      story: { inline: false, height: "720px" },
    },
  },
} satisfies Meta<typeof WorkspaceFrame>;

export default meta;
type Story = StoryObj<typeof meta>;

export const StandardSidebar: Story = { args: { sidebarSize: "standard" } };
export const WideSidebar: Story = { args: { sidebarSize: "wide" } };
export const CompactSidebar: Story = { args: { sidebarSize: "compact", responsiveCollapse: false } };
