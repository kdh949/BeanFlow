import { LifeBuoy, LogOut, ReceiptText, Sparkles } from "lucide-react";
import { useState } from "react";
import { Link, useNavigate } from "react-router";
import { ErrorState } from "../../../components/Ui";
import { PageTitle } from "../../../components/Shells";
import { customerSession, useCustomerSession } from "./customerSession";
import { Button } from "../../../design-system";

export function CustomerMyPage() {
  const session = useCustomerSession();
  const navigate = useNavigate();
  const [failure, setFailure] = useState<unknown>(null);
  const [signingOut, setSigningOut] = useState(false);

  if (session.status !== "authenticated") return null;

  async function signOut() {
    setSigningOut(true);
    setFailure(null);
    try {
      await customerSession.logOut();
      navigate("/app/login", { replace: true });
    } catch (error) {
      setFailure(error);
    } finally {
      setSigningOut(false);
    }
  }

  return (
    <div className="customer-page my-page">
      <PageTitle eyebrow="MY" title="내 정보" description="로그인한 계정과 주문·포인트 화면으로 이동할 수 있어요." />
      <section className="surface-card my-identity">
        <strong>{session.actor.displayName}</strong>
        <span>고객 계정으로 로그인되어 있습니다.</span>
      </section>
      <nav className="my-links" aria-label="내 정보 바로가기">
        <Link className="surface-card my-link" to="/app/orders"><ReceiptText size={19} /><span>주문 내역</span></Link>
        <Link className="surface-card my-link" to="/app/points"><Sparkles size={19} /><span>포인트</span></Link>
        <Link className="surface-card my-link" to="/app/help"><LifeBuoy size={19} /><span>도움말</span></Link>
      </nav>
      {failure ? <ErrorState error={failure} /> : null}
      <Button variant="secondary" block loading={signingOut} onClick={() => void signOut()}>
        <LogOut size={17} /> {signingOut ? "로그아웃 중" : "로그아웃"}
      </Button>
      <p className="form-footnote">로그아웃하면 이 기기의 장바구니와 진행 중이던 요청 키가 함께 지워집니다.</p>
    </div>
  );
}
