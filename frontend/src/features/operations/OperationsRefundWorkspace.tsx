import { useState } from "react";
import { Button, TextField } from "../../design-system";
import { RefundWorkspace } from "../../presentation/beanflow-refresh/MerchantPages";
import { StoreTargetPicker, type StoreSelection } from "./StoreTargetPicker";
/** Resolves a store-scoped public order before using the shared refund workflow. */
export function OperationsRefundWorkspace() {
  const [store, setStore] = useState<StoreSelection | null>(null);
  const [reference, setReference] = useState("");
  const [target, setTarget] = useState<{ store: StoreSelection; orderReference: string } | null>(null);
  if (target) return <section className="management-workspace"><p>대상 매장 <strong>{target.store.name}</strong></p><RefundWorkspace storeId={target.store.storeId} orderReference={target.orderReference} surface="operations" /></section>;
  return <section className="management-workspace"><h2>운영 품목 환불</h2><p>매장과 주문 번호를확인한 뒤 환불 품목과 수량을 선택합니다. 금액은 현재 환불 가능 상태를 기준으로 계산합니다.</p><form className="surface-card management-workspace" onSubmit={event => { event.preventDefault(); if (store && reference.trim()) setTarget({ store, orderReference: reference.trim() }); }}><StoreTargetPicker value={store} onValueChange={next => { setStore(next); setReference(""); }} /><TextField label="환불 대상 주문 번호" value={reference} onValueChange={setReference} required description="고객 주문 내역에 표시되는 BF로 시작하는 주문 번호입니다." /><Button type="submit" disabled={!store || !reference.trim()}>환불 대상 확인</Button></form></section>;
}
