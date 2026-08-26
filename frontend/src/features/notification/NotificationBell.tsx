import { Bell } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { Link } from "react-router";
import type { components } from "../../api/schema";
import { ApiRequestError, unwrap } from "../../api/client";
import { customerApi } from "../../api/customerClient";
import { CUSTOMER_NOTIFICATION_SUMMARY_CHANGED } from "./notificationSummary";

type NotificationSummary = components["schemas"]["NotificationSummary"];

type BellState =
  | { status: "loading" }
  | { status: "ready"; value: NotificationSummary }
  | { status: "unauthenticated" }
  | { status: "failed" };

/**
 * Customer notification entry point. A summary failure is shown separately
 * from a successful `hasUnread=false` response, so the chrome never turns an
 * unavailable inbox into a false all-read signal.
 */
export function NotificationBell() {
  const [state, setState] = useState<BellState>({ status: "loading" });
  const generation = useRef(0);

  const load = useCallback(async () => {
    const current = ++generation.current;
    setState((previous) => previous.status === "ready" ? previous : { status: "loading" });
    try {
      const value = unwrap(await customerApi.GET("/me/notification-summary"));
      if (generation.current === current) setState({ status: "ready", value });
    } catch (failure) {
      if (generation.current !== current) return;
      if (failure instanceof ApiRequestError && failure.status === 401) {
        setState({ status: "unauthenticated" });
      } else {
        setState({ status: "failed" });
      }
    }
  }, []);

  useEffect(() => {
    void load();
    const reload = () => void load();
    window.addEventListener(CUSTOMER_NOTIFICATION_SUMMARY_CHANGED, reload);
    return () => {
      generation.current += 1;
      window.removeEventListener(CUSTOMER_NOTIFICATION_SUMMARY_CHANGED, reload);
    };
  }, [load]);

  const hasUnread = state.status === "ready" && state.value.hasUnread;
  const failed = state.status === "failed";
  const label = failed
    ? "알림 상태를 확인하지 못했습니다. 알림함 열기"
    : hasUnread
      ? "읽지 않은 알림 있음. 알림함 열기"
      : "알림함 열기";

  return (
    <Link
      className={`icon-action notification-bell${hasUnread ? " has-unread" : ""}${failed ? " is-unavailable" : ""}`}
      to="/app/notifications"
      aria-label={label}
      aria-busy={state.status === "loading" ? "true" : undefined}
    >
      <Bell size={20} aria-hidden="true" />
      {hasUnread || failed ? (
        <span className="notification-bell-indicator" aria-hidden="true">{failed ? "!" : ""}</span>
      ) : null}
    </Link>
  );
}
