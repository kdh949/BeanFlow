export const CUSTOMER_NOTIFICATION_SUMMARY_CHANGED = "beanflow:customer-notification-summary-changed";

/**
 * Tells every customer-shell consumer to re-read the server-owned unread
 * summary. The event carries no customer data and is never treated as the
 * summary itself.
 */
export function publishCustomerNotificationSummaryChanged() {
  window.dispatchEvent(new Event(CUSTOMER_NOTIFICATION_SUMMARY_CHANGED));
}
