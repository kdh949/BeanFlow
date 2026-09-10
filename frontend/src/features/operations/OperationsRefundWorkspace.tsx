import { useState } from "react";
import { Button, TextField } from "../../design-system";
import { RefundWorkspace } from "../../presentation/beanflow-refresh/MerchantPages";
/** Resolves a store-scoped public order before using the shared refund workflow. */
export function OperationsRefundWorkspace() {
  const [storeId, setStoreId] = useState("");
  const [reference, setReference] = useState("");
  const [target, setTarget] = useState<{ storeId: string; orderReference: string } | null>(null);
  if (target) return <section className="management-workspace"><p>대상 매장 <span className="support-case-reference">{target.storeId}</span></p><RefundWorkspace storeId={target.storeId} orderReference={target.orderReference} surface="operations" /></section>;
  return <section className="management-workspace"><h2>운영 품목 환불</h2><p>매장과 주문 번호를 확인한 뒤 환불 품목과 수량을 선택합니다. 금액은 현재 환불 가능 상태를 기준으로 계산합니다.</p><form className="surface-card management-workspace" onSubmit={event => { event.preventDefault(); if (storeId.trim() && reference.trim()) setTarget({ storeId: storeId.trim(), orderReference: reference.trim() }); }}><TextField label="환불 대상 매장 ID" value={storeId} onValueChange={setStoreId} required /><TextField label="환불 대상 주문 번호" value={reference} onValueChange={setReference} required /><Button type="submit" disabled={!storeId.trim() || !reference.trim()}>환불 대상 확인</Button></form></section>;
}
