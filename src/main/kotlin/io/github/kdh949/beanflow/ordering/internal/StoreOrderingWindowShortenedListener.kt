package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.merchant.api.StoreOrderingWindowShortened
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.sql.Timestamp

@Component
internal class StoreOrderingWindowShortenedListener(
    private val jdbc: JdbcTemplate,
) {
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    fun shorten(event: StoreOrderingWindowShortened) {
        jdbc.update(
            """
            UPDATE ordering_order
               SET ordering_window_closes_at = ?,
                   acceptance_deadline_at = CASE
                       WHEN state = 'PAID' THEN LEAST(acceptance_deadline_at, ?)
                       ELSE acceptance_deadline_at
                   END,
                   acceptance_warning_at = CASE
                       WHEN state = 'PAID'
                           AND paid_at + interval '2 minutes' < LEAST(acceptance_deadline_at, ?)
                           THEN paid_at + interval '2 minutes'
                       WHEN state = 'PAID' THEN NULL
                       ELSE acceptance_warning_at
                   END,
                   updated_at = GREATEST(updated_at, ?),
                   version = version + 1
             WHERE store_id = ?
               AND checkout_mode = 'IMMEDIATE'
               AND state IN ('PENDING_PAYMENT', 'PAID')
               AND ordering_window_closes_at > ?
            """.trimIndent(),
            Timestamp.from(event.shortenedClosesAt),
            Timestamp.from(event.shortenedClosesAt),
            Timestamp.from(event.shortenedClosesAt),
            Timestamp.from(event.changedAt),
            event.storeId,
            Timestamp.from(event.shortenedClosesAt),
        )
    }
}
