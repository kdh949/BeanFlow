import { useRef, useState } from "react";
import { ApiRequestError, SubmissionIntent } from "../../api/client";
/** Non-sensitive commands preserve one exact request while its response is uncertain. Never use for raw reveals. */
export function useSupportCommand(onSettled: () => void) {
  const intent = useRef(new SubmissionIntent());
  const attempt = useRef<{ key: string; run: (key: string) => Promise<unknown>; onSuccess: () => void; uncertain: boolean } | null>(null);
  const inFlight = useRef(false);
  const [busy, setBusy] = useState(false);
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  async function execute() {
    if (inFlight.current || !attempt.current) return;
    inFlight.current = true; setBusy(true); setFailure(null);
    const current = attempt.current;
    try {
      await current.run(current.key);
      current.onSuccess(); intent.current.complete(); attempt.current = null; setPending(false);
    } catch (error) {
      setFailure(error);
      const uncertain = error instanceof TypeError || (error instanceof DOMException && error.name === "AbortError") || (error instanceof ApiRequestError && (error.status >= 500 || error.code === "IDEMPOTENCY_REQUEST_IN_PROGRESS"));
      // A denial on retry does not establish whether the earlier request committed.
      current.uncertain ||= uncertain;
      setPending(current.uncertain);
      if (!current.uncertain) attempt.current = null;
    } finally {
      onSettled(); inFlight.current = false; setBusy(false);
    }
  }
  function submit(fingerprint: string, run: (key: string) => Promise<unknown>, onSuccess: () => void) {
    if (inFlight.current || attempt.current?.uncertain) return;
    attempt.current = { key: intent.current.keyFor(fingerprint), run, onSuccess, uncertain: false };
    void execute();
  }
  return { submit, retry: execute, busy, pending, failure };
}
