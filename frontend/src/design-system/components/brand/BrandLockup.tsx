import { Link } from "react-router";

export type BrandLockupProps = { to?: string; compact?: boolean };

/** BeanFlow cup mark and wordmark used on every customer and workspace shell. */
export function BrandLockup({ to, compact = false }: BrandLockupProps) {
  const content = <><img src="/brand/beanflow-cup-mark.svg" alt="" /><span>{compact ? "BF" : "BeanFlow"}</span></>;
  return to
    ? <Link className="bf-brand" to={to} aria-label="BeanFlow 홈">{content}</Link>
    : <span className="bf-brand" aria-label="BeanFlow">{content}</span>;
}
