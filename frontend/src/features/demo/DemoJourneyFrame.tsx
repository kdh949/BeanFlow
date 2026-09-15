import type { ReactNode } from "react";
import { useLocation } from "react-router";
import { ArrowRightLeft, LogOut } from "lucide-react";
import { Button, InlineNotice } from "../../design-system";
import { DemoGuide } from "./DemoGuide";
import { demoGuideView } from "./demoGuideModel";
import { useDemo } from "./DemoProvider";

/** Adds demo guidance around existing route content; outside a demo it renders children unchanged. */
export function DemoJourneyFrame({ surface, children }: { surface: "store" | "customer" | "none"; children: ReactNode }) {
  const demo = useDemo(); const location = useLocation();
  if (!demo?.session || surface === "none") return <>{children}</>;
  const { session } = demo;
  const view = demoGuideView({ status: session.order?.status ?? null, surface, pathname: location.pathname,
    pickupNumber: session.order?.pickupNumber, customerChecked: demo.customerChecked, expired: session.status !== "ACTIVE" });
  if (!demo.config?.testPaymentEnabled) view.actions = view.actions.filter((action) => action.kind !== "direct");
  return <div className={`demo-frame demo-frame--${surface}`}>
    <div className="demo-session-bar"><div><strong>체험 모드</strong><span>{surface === "store" ? "점주 화면" : "고객 화면"} · {session.storeName}</span></div>
      <div><Button variant="secondary" size="sm" disabled={demo.busy} onClick={() => void demo.act(surface === "store" ? "customer" : "merchant")}><ArrowRightLeft size={15} aria-hidden="true" />{surface === "store" ? "고객 화면" : "점주 화면"}</Button><Button variant="ghost" size="sm" disabled={demo.busy} onClick={() => void demo.act("exit")}><LogOut size={15} aria-hidden="true" />체험 종료</Button></div>
    </div>
    {demo.error ? <InlineNotice tone="warning" announce="assertive" title="체험 상태 확인이 필요해요" description={demo.error} action={<Button variant="secondary" onClick={() => void demo.refresh()}>상태 다시 확인</Button>} /> : null}
    <div className="demo-frame-content"><div className="demo-product-content">{children}</div><DemoGuide view={view} busy={demo.busy || Boolean(demo.error)} onAction={(action) => void demo.act(action)} /></div>
  </div>;
}
