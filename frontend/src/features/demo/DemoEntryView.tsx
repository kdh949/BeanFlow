import { ArrowRight, Coffee, ShieldCheck } from "lucide-react";
import { BrandLockup, Button, ButtonLink, InlineNotice } from "../../design-system";
import "./demo.css";

/** Entry composition for the dedicated visitor workspace; identifiers are issued only after start. */
export function DemoEntryView({ available, directAvailable = true, busy = false, resumable = false, error, onStart, onResume }: {
  available: boolean; directAvailable?: boolean; busy?: boolean; resumable?: boolean; error?: string;
  onStart: (mode: "GUIDED" | "DIRECT") => void; onResume: () => void;
}) {
  return <div className="demo-entry">
    <header className="demo-entry-header"><BrandLockup to="/demo" /><ButtonLink variant="ghost" to="/">BeanFlow 홈</ButtonLink></header>
    <main className="demo-entry-main">
      <section className="demo-entry-hero" aria-labelledby="demo-entry-title">
        <p className="demo-entry-label">BeanFlow 체험</p>
        <h1 id="demo-entry-title">주문 한 건이<br />픽업으로 이어지는 과정</h1>
        <p className="demo-entry-description">점주가 주문을 처리하고, 고객 화면에 반영되는 과정을 직접 확인해보세요.</p>
        <ol className="demo-entry-journey">{["주문 접수", "제조·준비", "고객 화면 확인", "픽업 완료"].map((step, i) => <li key={step}>{i > 0 ? <ArrowRight size={18} aria-hidden="true" /> : null}<span>{step}</span></li>)}</ol>
        {error ? <InlineNotice tone="danger" announce="assertive" title="체험을 시작하지 못했어요" description={error} /> : null}
        {!available ? <InlineNotice title="체험을 준비하고 있어요" description="지금은 체험 공간을 발급할 수 없어요. 잠시 후 다시 방문해 주세요." /> : null}
        <div className="demo-entry-actions">{resumable ? <Button size="xl" loading={busy} onClick={onResume}>진행 중인 체험 이어하기</Button> : <>
          <Button size="xl" loading={busy} disabled={!available} onClick={() => onStart("GUIDED")}>주문 처리 체험 시작</Button>
          {directAvailable ? <Button variant="secondary" size="xl" disabled={!available || busy} onClick={() => onStart("DIRECT")}>직접 메뉴를 골라 주문하기</Button> : null}
        </>}</div>
        <div className="demo-entry-reassurance"><ShieldCheck size={18} aria-hidden="true" /><p>회원가입 없이 나만의 체험 공간으로 시작합니다.<br />샘플 데이터와 테스트 결제로 진행하며 실제 청구는 발생하지 않습니다.</p></div>
        {busy ? <p role="status">전용 계정과 주문을 준비하고 있어요. 잠시만 기다려 주세요.</p> : null}
      </section>
      <section className="surface-card demo-entry-preview" aria-labelledby="demo-preview-title">
        <h2 id="demo-preview-title">체험할 주문</h2><p className="demo-preview-store">BeanFlow 체험점 <span>샘플 주문</span></p>
        <Coffee size={36} aria-hidden="true" /><h3>아이스 아메리카노</h3>
        <p>1개 · 4,500원</p><dl><div><dt>결제 방식</dt><dd>체험 포인트 전액 사용</dd></div><div><dt>주문·픽업 번호</dt><dd>시작할 때 발급</dd></div><div><dt>체험 시간</dt><dd>30분</dd></div></dl>
        <p className="demo-preview-note">시작하면 새 주문을 준비해드려요.<br />안내에 따라 주문 접수부터 처리해보세요.</p>
      </section>
    </main>
    <footer className="demo-entry-footer">체험 중에는 고객과 점주 화면을 오갈 수 있습니다.</footer>
  </div>;
}
