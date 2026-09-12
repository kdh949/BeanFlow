import { Checkbox, InlineNotice, SelectField, TextField } from "../../design-system";
import { isRefundResolution, isStoreResolution, resolutionOutcomeLabels, resolutionResponsibilityLabels, type ResolutionDraft } from "../../lib/supportResolutionPayload";
/** Typed resolution plan fields. Monetary and restoration values follow the selected outcome. */
export function SupportResolutionPlanFields({ value, onChange, disabled }: { value: ResolutionDraft; onChange: (next: ResolutionDraft) => void; disabled: boolean }) {
  const refund = isRefundResolution(value.outcome), store = isStoreResolution(value.responsibility);
  return <div className="management-workspace">
    <SelectField label="해결 방식" value={value.outcome} onValueChange={outcome => onChange({ ...value, outcome: outcome as ResolutionDraft["outcome"], cash: "", restorePoints: false, restoreCoupon: false, settlement: "" })} disabled={disabled}>{Object.entries(resolutionOutcomeLabels).map(([id, label]) => <option key={id} value={id}>{label}</option>)}</SelectField>
    <SelectField label="비용 책임" value={value.responsibility} onValueChange={responsibility => onChange({ ...value, responsibility: responsibility as ResolutionDraft["responsibility"], settlement: "" })} disabled={disabled}>{Object.entries(resolutionResponsibilityLabels).map(([id, label]) => <option key={id} value={id}>{label}</option>)}</SelectField>
    {refund ? <><TextField label="현금 환불 금액" type="number" min="1" step="1" max={Number.MAX_SAFE_INTEGER} value={value.cash} onValueChange={cash => onChange({ ...value, cash })} required disabled={disabled} description="현재 환불 가능 금액과 승인 근거를 확인해 원 단위로 입력합니다." /><Checkbox label="사용 포인트 복원" checked={value.restorePoints} onCheckedChange={restorePoints => onChange({ ...value, restorePoints })} disabled={disabled} /><Checkbox label="사용 쿠폰 복원" checked={value.restoreCoupon} onCheckedChange={restoreCoupon => onChange({ ...value, restoreCoupon })} disabled={disabled} /></> : null}
    {store && value.outcome !== "MANUAL_SETTLEMENT_REVIEW" ? <TextField label="매장 정산 조정 금액" type="number" max="-1" step="1" value={value.settlement} onValueChange={settlement => onChange({ ...value, settlement })} required disabled={disabled} description="매장 부담분을 음수 원 단위로 입력합니다. 환불 금액에서 자동 추정하지 않습니다." /> : null}
    {value.responsibility === "UNDETERMINED" ? <InlineNotice tone="warning" title="비용 책임이 미확정입니다" description="정산 조정이 차단될 수 있습니다. 근거를 확인해 책임을 결정해 주세요." /> : null}
    <TextField label="해결 증빙 참조" value={value.evidence} onValueChange={evidence => onChange({ ...value, evidence })} maxLength={500} required disabled={disabled} description="승인 요청과 동일한 증빙 참조를 입력합니다. 개인정보 원문은 입력하지 않습니다." />
  </div>;
}
