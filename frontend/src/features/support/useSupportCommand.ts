import { useRef, useState } from "react";
import { ApiRequestError } from "../../api/client";
import { authToken } from "../../auth/session";
import { supportDigest } from "../../lib/supportOrderPayload";

type Attempt = { actor: string; key: string; journalKey: string; uncertain: boolean; run: (key: string) => Promise<unknown>; onSuccess: () => void };
const journalPrefix = "beanflow.support-command.v1.";

function operatorJournalActor(): string {
  // This claim only namespaces local command identity; the server authorizes every request.
  const encoded = authToken.get().split(".")[1];
  if (!encoded) throw new Error("Missing operator identity");
  const claims: unknown = JSON.parse(atob(encoded.replace(/-/g, "+").replace(/_/g, "/")));
  if (!claims || typeof claims !== "object" || !("sub" in claims) || typeof claims.sub !== "string" || !claims.sub) throw new Error("Missing operator subject");
  return JSON.stringify(["operator", "iss" in claims ? claims.iss : "", claims.sub]);
}

/** Non-sensitive command identity survives same-tab re-entry. Raw payloads and reveals never enter storage. */
export function useSupportCommand(scope: string, onSettled: () => void, getActor: () => string | Promise<string> = operatorJournalActor) {
  const attempt = useRef<Attempt | null>(null);
  const inFlight = useRef(false);
  const [busy, setBusy] = useState(false);
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  async function execute() {
    if (inFlight.current || !attempt.current) return;
    inFlight.current = true; setBusy(true); setFailure(null);
    const current = attempt.current;
    try {
      if (await getActor() !== current.actor) throw new ApiRequestError(403, "ACCESS_DENIED", "Command actor changed");
      await current.run(current.key);
      sessionStorage.removeItem(current.journalKey);
      current.onSuccess(); attempt.current = null; setPending(false);
    } catch (error) {
      setFailure(error);
      current.uncertain ||= error instanceof TypeError || (error instanceof DOMException && error.name === "AbortError") || (error instanceof ApiRequestError && (error.status >= 500 || error.status === 408 || error.code === "IDEMPOTENCY_REQUEST_IN_PROGRESS"));
      setPending(current.uncertain);
      if (!current.uncertain) { sessionStorage.removeItem(current.journalKey); attempt.current = null; }
    } finally {
      onSettled(); inFlight.current = false; setBusy(false);
    }
  }
  function submit(fingerprint: string, run: (key: string) => Promise<unknown>, onSuccess: () => void) {
    if (inFlight.current || attempt.current?.uncertain) return;
    inFlight.current = true; setBusy(true); setFailure(null);
    void (async () => {
      try {
        const actor = await getActor();
        const journalKey = journalPrefix + await supportDigest(JSON.stringify([actor, scope, fingerprint]));
        const stored = sessionStorage.getItem(journalKey);
        const previous: unknown = stored === null ? null : JSON.parse(stored);
        if (previous !== null && (typeof previous !== "object" || !("key" in previous) || typeof previous.key !== "string" || !/^[a-f0-9-]{36}$/.test(previous.key))) throw new Error("Invalid command identity");
        const key = previous === null ? crypto.randomUUID() : (previous as { key: string }).key;
        // Record before dispatch, including the interval before a lost response is observable.
        sessionStorage.setItem(journalKey, JSON.stringify({ key }));
        attempt.current = { actor, key, journalKey, uncertain: previous !== null, run, onSuccess };
        inFlight.current = false;
        await execute();
      } catch (error) {
        setFailure(new ApiRequestError(503, "COMMAND_IDENTITY_UNAVAILABLE", "Command identity cannot be retained"));
        inFlight.current = false; setBusy(false);
      }
    })();
  }
  return { submit, retry: execute, busy, pending, failure };
}
