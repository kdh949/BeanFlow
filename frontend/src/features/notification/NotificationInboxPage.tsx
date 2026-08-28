import { ChevronRight, Megaphone, ReceiptText, RefreshCw } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { Link } from "react-router";
import type { components } from "../../api/schema";
import { unwrap } from "../../api/client";
import { customerApi, customerCsrfHeader } from "../../api/customerClient";
import { Button, EmptyState, LoadingState, PageHeading, Switch } from "../../design-system";
import { ErrorState } from "../../presentation/shared";
import { shortDateTime } from "../../lib/format";
import { publishCustomerNotificationSummaryChanged } from "./notificationSummary";

type NotificationPage = components["schemas"]["NotificationPage"];
type NotificationItem = components["schemas"]["NotificationItem"];
type NotificationPreference = components["schemas"]["NotificationPreference"];

export function NotificationInboxPage() {
  const [page, setPage] = useState<NotificationPage | null>(null);
  const [listError, setListError] = useState<unknown>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const [readingId, setReadingId] = useState<string | null>(null);
  const [readError, setReadError] = useState<unknown>(null);
  const listGeneration = useRef(0);

  const load = useCallback(async (cursor?: string, append = false) => {
    const current = ++listGeneration.current;
    if (append) setLoadingMore(true);
    else setPage(null);
    setListError(null);
    try {
      const next = unwrap(await customerApi.GET("/me/notifications", {
        params: { query: { cursor, limit: 20 } },
      }));
      if (listGeneration.current !== current) return;
      setPage((previous) => append && previous
        ? { items: [...previous.items, ...next.items], page: next.page }
        : next);
    } catch (failure) {
      if (listGeneration.current === current) setListError(failure);
    } finally {
      if (listGeneration.current === current) setLoadingMore(false);
    }
  }, []);

  useEffect(() => {
    void load();
    return () => {
      listGeneration.current += 1;
    };
  }, [load]);

  async function markRead(notificationId: string) {
    setReadingId(notificationId);
    setReadError(null);
    try {
      const header = await customerCsrfHeader();
      const result = await customerApi.PATCH("/me/notifications/{notificationId}", {
        params: { path: { notificationId }, header },
        body: { read: true },
      });
      if (!result.response.ok) unwrap(result);
      setPage((current) => current ? {
        ...current,
        items: current.items.map((item) => item.notificationId === notificationId
          ? { ...item, readAt: new Date().toISOString() }
          : item),
      } : current);
      publishCustomerNotificationSummaryChanged();
    } catch (failure) {
      setReadError(failure);
    } finally {
      setReadingId(null);
    }
  }

  return (
    <div className="customer-page notification-page">
      <PageHeading title="알림" />
      <NotificationPreferenceCard />
      {!page && !listError ? <LoadingState label="알림을 불러오는 중" /> : null}
      {listError ? <ErrorState error={listError} retry={() => void load()} /> : null}
      {page?.items.length === 0 ? (
        <EmptyState title="아직 받은 알림이 없어요" description="주문 상태나 신청한 혜택 소식이 생기면 여기에 표시됩니다." />
      ) : null}
      {readError ? (
        <div className="form-error" role="alert">
          읽음으로 표시하지 못했어요. 알림은 그대로 남아 있으니 다시 시도해 주세요.
        </div>
      ) : null}
      {page?.items.length ? (
        <ol className="notification-list" aria-label="받은 알림">
          {page.items.map((item) => (
            <NotificationRow
              key={item.notificationId}
              item={item}
              reading={readingId === item.notificationId}
              onRead={() => void markRead(item.notificationId)}
            />
          ))}
        </ol>
      ) : null}
      {page?.page.nextCursor ? (
        <Button block variant="secondary" loading={loadingMore} onClick={() => void load(page.page.nextCursor, true)}>
          <RefreshCw size={16} className={loadingMore ? "spin" : undefined} aria-hidden="true" />
          {loadingMore ? "더 불러오는 중" : "알림 더 보기"}
        </Button>
      ) : null}
    </div>
  );
}

function NotificationRow({ item, reading, onRead }: {
  item: NotificationItem;
  reading: boolean;
  onRead: () => void;
}) {
  const unread = !item.readAt;
  const classificationLabel = item.classification === "MARKETING" ? "혜택·소식" : "주문·거래";
  return (
    <li className={`surface-card notification-row${unread ? " is-unread" : ""}`}>
      <div className="notification-row-icon" aria-hidden="true">
        {item.classification === "MARKETING" ? <Megaphone size={19} /> : <ReceiptText size={19} />}
      </div>
      <div className="notification-row-copy">
        <div className="notification-row-meta">
          <span>{classificationLabel}</span>
          <time dateTime={item.createdAt}>{shortDateTime.format(new Date(item.createdAt))}</time>
        </div>
        <strong>{item.title}</strong>
        <p>{item.body}</p>
        <div className="notification-row-actions">
          {unread ? (
            <Button variant="ghost" size="sm" loading={reading} onClick={onRead}>
              {reading ? "저장 중" : "읽음으로 표시"}
            </Button>
          ) : <span className="notification-read-label">읽음</span>}
          {item.target.type === "ORDER" && item.target.reference ? (
            <Link to={`/app/orders/${item.target.reference}`}>
              주문 보기 <ChevronRight size={15} aria-hidden="true" />
            </Link>
          ) : null}
        </div>
      </div>
    </li>
  );
}

function NotificationPreferenceCard() {
  const [preference, setPreference] = useState<NotificationPreference | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    setPreference(null);
    setError(null);
    try {
      setPreference(unwrap(await customerApi.GET("/me/notification-preferences")));
    } catch (failure) {
      setError(failure);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  async function replace(marketingOptIn: boolean) {
    setSaving(true);
    setError(null);
    try {
      const header = await customerCsrfHeader();
      setPreference(unwrap(await customerApi.PUT("/me/notification-preferences", {
        params: { header },
        body: { marketingOptIn },
      })));
    } catch (failure) {
      setError(failure);
    } finally {
      setSaving(false);
    }
  }

  return (
    <section className="surface-card notification-preference" aria-labelledby="notification-preference-title">
      <div>
        <span className="context-label">알림 수신 설정</span>
        <h2 id="notification-preference-title">마케팅 알림</h2>
        <p>주문과 연결되지 않은 보상·혜택 소식을 알림함에서 받습니다. 주문 상태 알림은 이 설정과 무관하게 계속 표시됩니다.</p>
      </div>
      {!preference && !error ? <p role="status">수신 설정을 불러오는 중</p> : null}
      {preference ? (
        <Switch
          label="마케팅 알림 받기"
          description={saving ? "설정을 저장하는 중입니다." : "끄더라도 이미 받은 알림은 보관 기간 동안 남습니다."}
          checked={preference.marketingOptIn}
          disabled={saving}
          onCheckedChange={(checked) => void replace(checked)}
        />
      ) : null}
      {error ? (
        <div className="notification-preference-error" role="alert">
          <p>알림 설정을 확인하지 못했어요. 다시 불러와 현재 설정을 확인해 주세요.</p>
          <Button variant="secondary" size="sm" onClick={() => void load()}>다시 시도</Button>
        </div>
      ) : null}
    </section>
  );
}
