import { useRef, useState } from "react";
import { ApiRequestError, SubmissionIntent } from "../../api/client";

/** Retains only a digest and key after uncertainty; callers must re-enter raw fields for a matching retry. */
export function useSensitiveSupportCommand() {
  const intent = useRef(new SubmissionIntent()), inFlight = useRef(false);
  const [pending, setPending] = useState<{ fingerprint: string; key: string } | null>(null);
  const [mismatch, setMismatch] = useState(false);
  const [busy, setBusy] = useState(false), [failure, setFailure] = useState<unknown>(null);
  async function submit<T>(fingerprint: string, run: (key: string) => Promise<T>, onSuccess: (value: T) => void) {
    if (inFlight.current) return;
    setMismatch(false);
    if (pending && pending.fingerprint !== fingerprint) { setMismatch(true); setFailure(new ApiRequestError(409, "RESOURCE_STATE_CONFLICT", "처음 제출한 내용과 일치하지 않습니다")); return; }
    inFlight.current = true; setBusy(true); setFailure(null);
    const key = pending?.key ?? intent.current.keyFor(fingerprint);
    try { const result = await run(key); intent.current.complete(); setPending(null); onSuccess(result); }
    catch (error) {
      setFailure(error);
      const uncertain = error instanceof TypeError || (error instanceof DOMException && error.name === "AbortError") || (error instanceof ApiRequestError && (error.status >= 500 || error.code === "IDEMPOTENCY_REQUEST_IN_PROGRESS"));
      setPending(uncertain ? { fingerprint, key } : null);
    } finally { inFlight.current = false; setBusy(false); }
  }
  return { submit, pending, busy, failure, mismatch };
}
