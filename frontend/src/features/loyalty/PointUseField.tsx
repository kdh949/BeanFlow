import { useCallback, useState } from "react";
import type { components } from "../../api/schema";
import { unwrap } from "../../api/client";
import { customerApi } from "../../api/customerClient";
import { Button, TextField, FeedbackState } from "../../design-system";
import { ErrorState } from "../../presentation/shared";
import { useResource } from "../shared/useResource";

type PointSummary = components["schemas"]["CustomerPointSummary"];

export function usePointUse() {
  const balance = useResource<PointSummary>(useCallback(async () => unwrap(await customerApi.GET("/me/points")), []));
  const [value, setValue] = useState("");
  const amount = value === "" ? 0 : Number(value);
  const error = !/^\d*$/.test(value) || !Number.isSafeInteger(amount)
    ? "포인트는 0 이상의 정수로 입력해 주세요."
    : balance.state.status === "ready" && amount > balance.state.value.availablePointsKrw
      ? "보유 포인트를 초과했어요." : undefined;
  const valid = !error && (amount === 0 || balance.state.status === "ready");
  return { balance, value, setValue, amount, error, valid };
}

/** Explicit point amount input; balance and order limits never replace server validation. */
export function PointUseField({ selection, maximum, allowFullUse = true, disabled = false }: {
  selection: ReturnType<typeof usePointUse>;
  /** Coupon-adjusted amount from the current server quote; omitted until available. */
  maximum?: number;
  /** Reorders have no pre-creation quote and accept an explicit amount only. */
  allowFullUse?: boolean;
  disabled?: boolean;
}) {
  const { balance, value, setValue, error } = selection;
  const ready = balance.state.status === "ready";
  const available = balance.state.status === "ready" ? balance.state.value.availablePointsKrw : undefined;
  return <section className="bfr-point-use" aria-label="포인트 사용">
    <h2>포인트</h2>
    {balance.state.status === "loading" ? <FeedbackState kind="loading" title="포인트를 확인하는 중" description="잠시만 기다려 주세요." /> : null}
    {balance.state.status === "failed" ? <ErrorState error={balance.state.error} retry={balance.reload} /> : null}
    {available !== undefined ? <p>사용 가능 <strong>{available.toLocaleString("ko-KR")}P</strong></p> : null}
    <TextField label="사용할 포인트" size="lg" inputMode="numeric" value={value} onValueChange={setValue} error={error} disabled={disabled || !ready} placeholder="0" description="1P는 1원입니다. 쿠폰 할인 후 남은 금액까지 사용할 수 있어요." />
    <div className="bfr-point-actions">
      {allowFullUse ? <Button variant="secondary" disabled={disabled || maximum === undefined || available === undefined || available === 0} onClick={() => { if (maximum !== undefined && available !== undefined) setValue(String(Math.min(available, maximum))); }}>전액 사용</Button> : null}
      <Button variant="ghost" disabled={disabled || value === "" || value === "0"} onClick={() => setValue("")}>사용 안 함</Button>
    </div>
    {maximum === undefined ? <p className="form-footnote">최종 사용 가능 금액은 주문 조건을 확인한 뒤 결정돼요.</p> : null}
  </section>;
}
