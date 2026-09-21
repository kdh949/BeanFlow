import { Check, ChevronDown, ChevronUp } from "lucide-react";
import { useId, useState } from "react";
import { Button } from "../../design-system";
import type { DemoAction, DemoGuideView } from "./demoGuideModel";
import "./demo.css";

/** Collapsible instructions. The model contains only server-confirmed progress and navigation actions. */
export function DemoGuide({ view, onAction, busy = false }: {
  view: DemoGuideView; onAction: (action: DemoAction) => void; busy?: boolean;
}) {
  const [open, setOpen] = useState(true);
  const contentId = useId();
  return <aside className="demo-guide" aria-label="주문 체험 안내" data-collapsed={!open}>
    <Button variant="ghost" block aria-expanded={open} aria-controls={contentId} aria-label={`체험 안내 ${open ? "접기" : "펼치기"}`} onClick={() => setOpen(!open)}>
      체험 안내 {open ? <ChevronUp size={18} aria-hidden="true" /> : <ChevronDown size={18} aria-hidden="true" />}
    </Button>
    <div id={contentId} hidden={!open} className="demo-guide-body">
      <p className="demo-guide-progress">{Math.min(view.step + 1, view.steps.length)} / {view.steps.length} · {view.steps[Math.min(view.step, view.steps.length - 1)]}</p>
      <div aria-live="polite" aria-atomic="true"><h2>{view.title}</h2><p>{view.body}</p></div>
      {view.actions.length ? <div className="demo-guide-actions">{view.actions.map(({ kind, label }, index) => <Button key={kind} variant={index === 0 && kind === "restart" ? "brand" : "secondary"} block disabled={busy} onClick={() => onAction(kind)}>{label}</Button>)}</div> : null}
      <ol className="demo-guide-steps">{view.steps.map((step, index) => <li key={step} aria-current={index === view.step ? "step" : undefined} data-complete={index < view.step}>
        <span aria-hidden="true">{index < view.step ? <Check size={17} /> : index + 1}</span><span>{step}{index < view.step ? <span className="bf-sr-only"> 완료</span> : null}</span>
      </li>)}</ol>
      <p className="demo-guide-footer">{view.footer}</p>
    </div>
  </aside>;
}
