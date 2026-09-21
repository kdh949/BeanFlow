import { Button, FeedbackState, InlineNotice } from "../../design-system";
import { useSearchParams } from "react-router";
import { DemoEntryView } from "./DemoEntryView";
import { useDemo } from "./DemoProvider";
export function DemoEntryPage() {
  const demo = useDemo(); const [params] = useSearchParams();
  if (!demo) throw new Error("DemoEntryPage requires DemoProvider");
  if (demo.checking && !demo.config) return <FeedbackState kind="loading" title="체험 환경을 확인하고 있어요" description="잠시만 기다려 주세요." />;
  return <>
    {params.has("expired") ? <InlineNotice tone="warning" title="체험 시간이 끝났어요" description="기존 체험 공간의 접근이 종료됐어요. 새 공간으로 다시 시작할 수 있어요." /> : null}
    <DemoEntryView available={demo.config?.enabled === true} directAvailable={demo.config?.testPaymentEnabled === true} busy={demo.busy} resumable={demo.session?.status === "ACTIVE"}
      error={demo.error} onStart={(mode) => void demo.start(mode)} onResume={() => void demo.resume()} />
    {demo.error ? <Button variant="secondary" onClick={() => void demo.refresh()}>체험 환경 다시 확인</Button> : null}
  </>;
}
