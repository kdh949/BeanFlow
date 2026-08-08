package io.github.kdh949.beanflow.merchant.internal

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

internal data class StoreMenuProjection(
    val menuId: UUID,
    val name: String,
    val basePriceKrw: Long,
    val available: Boolean,
)

internal data class StoreMenuOptionProjection(
    val menuId: UUID,
    val optionId: UUID,
    val name: String,
    val additionalPriceKrw: Long,
    val available: Boolean,
)

/**
 * The published catalogue bounds (ADR-076). They sit far above any
 * plausible cafe catalogue and exist so that one store can never make the response unbounded. Each
 * query asks for one row past its bound: [StoreMenuQueryService] sees the overflow and fails
 * explicitly rather than returning a silently truncated catalogue.
 */
internal const val MAX_STORE_MENUS = 1_000

internal const val MAX_STORE_MENU_OPTIONS = 5_000

/**
 * Reads the store menu catalogue as two flat DTO queries — one for menus and one for every option
 * of those menus — so the number of statements stays constant regardless of how many menus a store
 * has. The write entities keep no association between store, menu and option, and none is added
 * here for listing convenience.
 */
@Repository
internal class StoreMenuQueryRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun findMenus(storeId: UUID): List<StoreMenuProjection> =
        jdbcTemplate.query(
            """
            SELECT id, name, base_price_krw, available
              FROM merchant_menu
             WHERE store_id = ?
             ORDER BY name, id
             LIMIT ${MAX_STORE_MENUS + 1}
            """.trimIndent(),
            { resultSet, _ ->
                StoreMenuProjection(
                    menuId = resultSet.getObject("id", UUID::class.java),
                    name = resultSet.getString("name"),
                    basePriceKrw = resultSet.getLong("base_price_krw"),
                    available = resultSet.getBoolean("available"),
                )
            },
            storeId,
        )

    /**
     * Options are selected through the store predicate rather than a menu-id list, so one statement
     * covers every menu of the store.
     */
    fun findOptions(storeId: UUID): List<StoreMenuOptionProjection> =
        jdbcTemplate.query(
            """
            SELECT menu_option.menu_id, menu_option.id, menu_option.name,
                   menu_option.additional_price_krw, menu_option.available
              FROM merchant_menu_option menu_option
              JOIN merchant_menu menu ON menu.id = menu_option.menu_id
             WHERE menu.store_id = ?
             ORDER BY menu_option.name, menu_option.id
             LIMIT ${MAX_STORE_MENU_OPTIONS + 1}
            """.trimIndent(),
            { resultSet, _ ->
                StoreMenuOptionProjection(
                    menuId = resultSet.getObject("menu_id", UUID::class.java),
                    optionId = resultSet.getObject("id", UUID::class.java),
                    name = resultSet.getString("name"),
                    additionalPriceKrw = resultSet.getLong("additional_price_krw"),
                    available = resultSet.getBoolean("available"),
                )
            },
            storeId,
        )
}
