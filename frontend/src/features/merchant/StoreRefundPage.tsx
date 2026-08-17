import { RotateCcw } from "lucide-react";
import { useCallback, useRef, useState } from "react";
import { Link, useParams } from "react-router";
import type { components } from "../../api/schema";
import { ApiRequestError, SubmissionIntent, unwrap } from "../../api/client";
import { merchantApi, merchantCsrfHeader } from "../../api/merchantClient";
import { ErrorState, LoadingState, StatusBadge } from "../../components/Ui";
import { PageTitle } from "../../components/Shells";
import { Button } from "../../design-system";
import { won } from "../../lib/format";
import { useResource } from "../shared/useResource";

type Preview = components["schemas"]["MerchantRefundPreview"];
type PreviewLine = components["schemas"]["MerchantRefundPreviewLine"];
type RefundResult = components["schemas"]["MerchantRefundResult"];

type Selection = Record<number, number>;

/**
 * The request carries only the store-scoped order reference, the line sequences
 * shown on this screen and their quantities. Payment and OrderLine identifiers
 * never reach the browser, and the amounts are the server's, never the form's.
 */
async function requestPreview(
  storeId: string,
  orderReference: string,
  selection: Selection,
): Promise<Preview> {
  const lines = Object.entries(selection)
    .filter(([, quantity]) => quantity > 0)
    .map(([lineSequence, quantity]) => ({ lineSequence: Number(lineSequence), quantity }));
  return unwrap(
    await merchantApi.POST("/stores/{storeId}/orders/{orderReference}/refund-previews", {
      params: { path: { storeId, orderReference }, header: await merchantCsrfHeader() },
      body: lines.length > 0 ? { lines } : {},
    }),
  );
}

export function StoreRefundPage() {
  const { storeId = "", orderReference = "" } = useParams();
  const [selection, setSelection] = useState<Selection>({});
  const [reason, setReason] = useState("");
  const [result, setResult] = useState<RefundResult | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const [staleNotice, setStaleNotice] = useState(false);
  const refundIntent = useRef(new SubmissionIntent());
  const previewGeneration = useRef(0);

  const load = useCallback(() => requestPreview(storeId, orderReference, {}), [storeId, orderReference]);
  const { state, reload } = useResource<Preview>(load);
  const [preview, setPreview] = useState<Preview | null>(null);
  const current = preview ?? (state.status === "ready" ? state.value : null);

  /**
   * Quantity edits fire one preview request per keystroke with nothing to
   * cancel the previous one, so an older response can land after a newer one.
   * The generation guard keeps only the most recently started request's
   * answer, so a slow response for a superseded selection never overwrites
   * the amount and previewVersion the operator is about to submit.
   */
  async function reprice(next: Selection) {
    setSelection(next);
    setFailure(null);
    const generation = ++previewGeneration.current;
    try {
      const result = await requestPreview(storeId, orderReference, next);
      if (generation !== previewGeneration.current) return;
      setPreview(result);
    } catch (error) {
      if (generation !== previewGeneration.current) return;
      setFailure(error);
    }
  }

  function changeQuantity(line: PreviewLine, quantity: number) {
    const bounded = Math.max(0, Math.min(quantity, line.remainingQuantity));
    refundIntent.current.rotate();
    void reprice({ ...selection, [line.lineSequence]: bounded });
  }

  async function refresh() {
    setStaleNotice(false);
    refundIntent.current.rotate();
    await reprice(selection);
  }

  async function submit() {
    if (!current) return;
    const lines = current.lines
      .filter((line) => line.selectedQuantity > 0)
      .map((line) => ({ lineSequence: line.lineSequence, quantity: line.selectedQuantity }));
    if (lines.length === 0) return;
    const fingerprint = JSON.stringify({ storeId, orderReference, lines, reason: reason.trim() });
    setSubmitting(true);
    setFailure(null);
    setStaleNotice(false);
    try {
      const response = await merchantApi.POST("/stores/{storeId}/orders/{orderReference}/refunds", {
        params: {
          path: { storeId, orderReference },
          header: {
            "Idempotency-Key": refundIntent.current.keyFor(fingerprint),
            ...(await merchantCsrfHeader()),
          },
        },
        body: { lines, previewVersion: current.previewVersion, reason: reason.trim() },
      });
      setResult(unwrap(response));
      refundIntent.current.complete();
      setSelection({});
      await reprice({});
    } catch (error) {
      if (error instanceof ApiRequestError && error.code === "REFUND_PREVIEW_STALE") {
        setStaleNotice(true);
        refundIntent.current.rotate();
        await reprice(selection);
      } else {
        if (error instanceof ApiRequestError && error.code === "IDEMPOTENCY_KEY_REUSED") {
          refundIntent.current.rotate();
        }
        setFailure(error);
      }
    } finally {
      setSubmitting(false);
    }
  }

  if (state.status === "loading" && !preview) return <LoadingState label="환불 가능 품목을 불러오는 중" />;
  if (state.status === "failed" && !preview) {
    return (
      <div className="console-page narrow-console-page">
        <ErrorState error={state.error} retry={reload} />
      </div>
    );
  }
  if (!current) return <LoadingState label="환불 가능 품목을 불러오는 중" />;

  const selectedTotal = current.totals.cashRefundKrw + current.totals.pointsRestorationKrw;
  const refundable = current.lines.some((line) => line.remainingQuantity > 0);
  return (
    <div className="console-page narrow-console-page">
      <PageTitle
        eyebrow={`ORDER ${current.orderReference}`}
        title="품목 부분 환불"
        description="환불할 품목과 수량을 고르면 서버가 계산한 금액을 확인한 뒤 실행합니다."
        action={<Link className="back-link" to="/store">주문 보드로</Link>}
      />

      {result ? <RefundOutcome result={result} /> : null}

      {refundable ? null : (
        <p className="form-footnote" role="status">이 주문에는 더 환불할 수 있는 품목이 없습니다.</p>
      )}

      <section className="surface-card refund-lines">
        <h2>환불 품목</h2>
        <ul>
          {current.lines.map((line) => (
            <li key={line.lineSequence}>
              <div className="refund-line-name">
                <strong>{line.menuName}</strong>
                <small>남은 환불 가능 수량 {line.remainingQuantity}개</small>
              </div>
              <label className="refund-line-quantity">
                <span>환불 수량</span>
                <input
                  type="number"
                  min={0}
                  max={line.remainingQuantity}
                  value={line.selectedQuantity}
                  disabled={line.remainingQuantity === 0 || submitting}
                  onChange={(event) => changeQuantity(line, Number(event.target.value))}
                />
              </label>
              <dl className="refund-line-amounts">
                <div><dt>현금</dt><dd className="bf-num">{won.format(line.cashRefundKrw)}</dd></div>
                <div><dt>포인트</dt><dd className="bf-num">{won.format(line.pointsRestorationKrw)}</dd></div>
                <div><dt>쿠폰 귀속</dt><dd className="bf-num">{won.format(line.couponAttributionKrw)}</dd></div>
              </dl>
            </li>
          ))}
        </ul>
      </section>

      <section className="surface-card refund-totals">
        <div><span>현금 환불</span><strong className="bf-num">{won.format(current.totals.cashRefundKrw)}</strong></div>
        <div><span>포인트 복원</span><strong className="bf-num">{won.format(current.totals.pointsRestorationKrw)}</strong></div>
        <p className="form-footnote">
          쿠폰 귀속액 {won.format(current.totals.couponAttributionKrw)}은 쿠폰을 되돌려 주지 않습니다. 표시 금액은 서버 계산값이며 실행 시 다시 검증합니다.
        </p>
      </section>

      {staleNotice ? (
        <p className="form-error" role="alert">
          다른 요청이 먼저 처리되어 금액이 바뀌었습니다. 새로 계산한 금액을 확인한 뒤 다시 실행해 주세요.
        </p>
      ) : null}
      {failure ? <ErrorState error={failure} retry={() => void refresh()} /> : null}

      <form
        className="surface-card operation-form"
        onSubmit={(event) => {
          event.preventDefault();
          void submit();
        }}
      >
        <label htmlFor="refund-reason">환불 사유</label>
        <textarea
          id="refund-reason"
          value={reason}
          maxLength={500}
          required
          placeholder="어떤 문제로 환불하는지 적어 주세요"
          onChange={(event) => {
            setReason(event.target.value);
            refundIntent.current.rotate();
          }}
        />
        <div className="refund-actions">
          <Button variant="secondary" type="button" onClick={() => void refresh()} disabled={submitting}>
            금액 다시 계산
          </Button>
          <Button type="submit" loading={submitting} disabled={selectedTotal <= 0 || !reason.trim()}>
            <RotateCcw size={17} /> {submitting ? "환불 요청 중" : "부분 환불 실행"}
          </Button>
        </div>
      </form>
    </div>
  );
}

function assertNever(value: never): never {
  throw new Error(`Unhandled refund state: ${String(value)}`);
}

/**
 * A definitive success, a definitive failure, a case that needs a human, and an
 * unresolved Provider outcome are four different answers. Collapsing them into
 * one "확인 중" message would tell an operator to wait forever on a refund that
 * has already failed or needs manual follow-up, so every contract state gets
 * its own heading and body. The `never` branch below fails the build if the
 * contract ever adds a state this screen has not been taught to show.
 */
function refundOutcomeCopy(result: RefundResult): { heading: string; body: string } {
  switch (result.state) {
    case "SUCCEEDED":
      return {
        heading: "현금 환불이 확인되었습니다",
        body: `${won.format(result.cashRefundedKrw ?? result.cashRefundRequestedKrw)} 환불이 확정되었습니다.`,
      };
    case "FAILED":
      return {
        heading: "환불에 실패했습니다",
        body: "환불을 처리하지 못했습니다. 문의 코드를 남기고 운영팀에 알려 주세요. 같은 요청을 다시 보내도 새 환불이 만들어지지 않습니다.",
      };
    case "MANUAL_REVIEW":
      return {
        heading: "운영팀 확인이 필요합니다",
        body: "자동 처리로는 결과를 확정하지 못해 운영팀이 확인하고 있습니다. 같은 요청을 다시 보내지 않아도 됩니다.",
      };
    case "REQUESTED":
    case "PROCESSING":
    case "RETRY_SCHEDULED":
    case "UNKNOWN":
    case "RECONCILING":
      return {
        heading: "환불 결과를 확인하고 있습니다",
        body: "아직 성공도 실패도 아닙니다. 같은 요청을 다시 보내도 새 환불이 만들어지지 않습니다.",
      };
    default:
      return assertNever(result.state);
  }
}

export function RefundOutcome({ result }: { result: RefundResult }) {
  const { heading, body } = refundOutcomeCopy(result);
  return (
    <section className="surface-card result-card">
      <div>
        <span className="eyebrow">REFUND {result.orderReference}</span>
        <StatusBadge state={result.state} />
      </div>
      <h2>{heading}</h2>
      <p>{body}</p>
      <dl className="detail-list">
        <div><dt>요청 현금</dt><dd className="bf-num">{won.format(result.cashRefundRequestedKrw)}</dd></div>
        <div><dt>포인트 복원</dt><dd>{result.pointsRestorationState}</dd></div>
        <div><dt>문의 코드</dt><dd><code>{result.correlationId}</code></dd></div>
      </dl>
    </section>
  );
}
