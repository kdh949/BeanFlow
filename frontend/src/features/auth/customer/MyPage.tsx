import { Heart, LifeBuoy, LogOut, ReceiptText, Sparkles, TicketPercent } from "lucide-react";
import { useState } from "react";
import { Link, useNavigate } from "react-router";
import { ErrorState } from "../../../presentation/shared";
import { PageHeading } from "../../../design-system";
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
      <PageHeading title="내 정보" />
      <section className="surface-card my-identity">
        <strong>{session.actor.displayName}</strong>
      </section>
      <nav className="my-links" aria-label="내 정보 바로가기">
        <Link className="surface-card my-link" to="/app/orders"><ReceiptText size={19} /><span>주문 내역</span></Link>
        <Link className="surface-card my-link" to="/app/points"><Sparkles size={19} /><span>포인트</span></Link>
        <Link className="surface-card my-link" to="/app/coupons"><TicketPercent size={19} /><span>쿠폰</span></Link>
        <Link className="surface-card my-link" to="/app/favorites"><Heart size={19} /><span>즐겨찾기 매장</span></Link>
        <Link className="surface-card my-link" to="/app/help"><LifeBuoy size={19} /><span>도움말</span></Link>
      </nav>
      {failure ? <ErrorState error={failure} /> : null}
      <Button variant="secondary" block loading={signingOut} onClick={() => void signOut()}>
        <LogOut size={17} /> {signingOut ? "로그아웃 중" : "로그아웃"}
      </Button>
      <p className="form-footnote">로그아웃하면 이 기기에 담아 둔 장바구니도 함께 비워져요.</p>
    </div>
  );
}
