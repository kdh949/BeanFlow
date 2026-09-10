package io.github.kdh949.beanflow.merchant.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "merchant_store")
internal class StoreEntity(
    @Id
    val id: UUID,
    @Column(name = "accepting_orders", nullable = false)
    var acceptingOrders: Boolean,
    @Column(name = "pickup_enabled", nullable = false)
    var pickupEnabled: Boolean,
    @Column(name = "ordering_policy_version", nullable = false)
    var orderingPolicyVersion: Long = 0,
    @Column(name = "ordering_policy_updated_at", nullable = false)
    var orderingPolicyUpdatedAt: Instant = Instant.EPOCH,
    @Column(name = "image_original_key")
    var imageOriginalKey: String? = null,
    @Column(name = "image_thumbnail_key")
    var imageThumbnailKey: String? = null,
    @Column(name = "image_sha256")
    var imageSha256: String? = null,
    @Column(name = "image_updated_at")
    var imageUpdatedAt: Instant? = null,
    @Version
    var version: Long = 0,
) {
    fun replaceOrderingPolicy(
        acceptingOrders: Boolean,
        pickupEnabled: Boolean,
        updatedAt: Instant,
    ) {
        this.acceptingOrders = acceptingOrders
        this.pickupEnabled = pickupEnabled
        orderingPolicyVersion = Math.addExact(orderingPolicyVersion, 1)
        orderingPolicyUpdatedAt = updatedAt
    }

    fun replaceImage(
        originalKey: String,
        thumbnailKey: String,
        sha256: String,
        updatedAt: Instant,
    ) {
        imageOriginalKey = originalKey
        imageThumbnailKey = thumbnailKey
        imageSha256 = sha256
        imageUpdatedAt = updatedAt
    }

    fun clearImage() {
        imageOriginalKey = null
        imageThumbnailKey = null
        imageSha256 = null
        imageUpdatedAt = null
    }
}

@Entity
@Table(name = "merchant_menu")
internal class MenuEntity(
    @Id
    val id: UUID,
    @Column(name = "store_id", nullable = false)
    val storeId: UUID,
    @Column(nullable = false)
    var name: String,
    @Column(name = "base_price_krw", nullable = false)
    var basePriceKrw: Long,
    @Column(nullable = false)
    var available: Boolean,
    @Column(name = "image_original_key")
    var imageOriginalKey: String? = null,
    @Column(name = "image_thumbnail_key")
    var imageThumbnailKey: String? = null,
    @Column(name = "image_sha256")
    var imageSha256: String? = null,
    @Column(name = "image_updated_at")
    var imageUpdatedAt: Instant? = null,
    @Column(name = "display_category")
    var displayCategory: String? = null,
    @Column(name = "public_description")
    var publicDescription: String? = null,
    @Version
    var version: Long = 0,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var lifecycle: MenuLifecycle = MenuLifecycle.ACTIVE,
    @Column(name = "trade_version", nullable = false)
    var tradeVersion: Long = 0,
    @Column(name = "trade_updated_at", nullable = false)
    var tradeUpdatedAt: Instant = Instant.EPOCH,
    @Column(name = "archived_at")
    var archivedAt: Instant? = null,
) {
    fun replaceImage(
        originalKey: String,
        thumbnailKey: String,
        sha256: String,
        updatedAt: Instant,
    ) {
        imageOriginalKey = originalKey
        imageThumbnailKey = thumbnailKey
        imageSha256 = sha256
        imageUpdatedAt = updatedAt
    }

    fun clearImage() {
        imageOriginalKey = null
        imageThumbnailKey = null
        imageSha256 = null
        imageUpdatedAt = null
    }

    fun replaceDisplayContent(
        displayCategory: String?,
        publicDescription: String?,
    ) {
        this.displayCategory = displayCategory
        this.publicDescription = publicDescription
    }

    fun replaceTradeContent(
        name: String,
        basePriceKrw: Long,
        available: Boolean,
        updatedAt: Instant,
    ) {
        this.name = name
        this.basePriceKrw = basePriceKrw
        this.available = available
        tradeVersion = Math.addExact(tradeVersion, 1)
        tradeUpdatedAt = updatedAt
    }

    fun archive(updatedAt: Instant) {
        check(lifecycle == MenuLifecycle.ACTIVE) { "Only an active Menu can be archived" }
        lifecycle = MenuLifecycle.ARCHIVED
        archivedAt = updatedAt
        tradeVersion = Math.addExact(tradeVersion, 1)
        tradeUpdatedAt = updatedAt
    }
}

internal enum class MenuLifecycle {
    ACTIVE,
    ARCHIVED,
}

@Entity
@Table(name = "merchant_menu_option")
internal class MenuOptionEntity(
    @Id
    val id: UUID,
    @Column(name = "menu_id", nullable = false)
    val menuId: UUID,
    @Column(nullable = false)
    var name: String,
    @Column(name = "additional_price_krw", nullable = false)
    var additionalPriceKrw: Long,
    @Column(nullable = false)
    var available: Boolean,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var lifecycle: MenuLifecycle = MenuLifecycle.ACTIVE,
    @Column(name = "archived_at")
    var archivedAt: Instant? = null,
)

@Entity
@Table(name = "merchant_menu_configuration")
internal class MenuConfigurationEntity(
    @Id
    val id: UUID,
    @Column(name = "menu_id", nullable = false)
    val menuId: UUID,
    @Column(name = "normalized_option_key", nullable = false)
    var normalizedOptionKey: String,
    @Column(nullable = false)
    var available: Boolean,
    @Version
    var version: Long = 0,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var lifecycle: MenuLifecycle = MenuLifecycle.ACTIVE,
    @Column(name = "archived_at")
    var archivedAt: Instant? = null,
)

internal interface StoreJpaRepository : JpaRepository<StoreEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT store FROM StoreEntity store WHERE store.id = :storeId")
    fun findByIdForShare(storeId: UUID): StoreEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT store FROM StoreEntity store WHERE store.id = :storeId")
    fun findByIdForUpdate(storeId: UUID): StoreEntity?
}

internal interface MenuJpaRepository : JpaRepository<MenuEntity, UUID> {
    fun countByStoreIdAndLifecycle(
        storeId: UUID,
        lifecycle: MenuLifecycle,
    ): Long

    fun findByIdAndStoreId(
        menuId: UUID,
        storeId: UUID,
    ): MenuEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT menu FROM MenuEntity menu WHERE menu.id = :menuId AND menu.storeId = :storeId")
    fun findByIdAndStoreIdForUpdate(
        menuId: UUID,
        storeId: UUID,
    ): MenuEntity?

    @Query(
        """
        SELECT menu FROM MenuEntity menu
         WHERE menu.storeId = :storeId
           AND menu.lifecycle = :lifecycle
           AND (:afterName IS NULL OR menu.name > :afterName OR (menu.name = :afterName AND menu.id > :afterMenuId))
         ORDER BY menu.name ASC, menu.id ASC
        """,
    )
    fun findCatalogPage(
        storeId: UUID,
        lifecycle: MenuLifecycle,
        afterName: String?,
        afterMenuId: UUID?,
        pageable: Pageable,
    ): List<MenuEntity>

    fun findAllByStoreIdAndLifecycleOrderByNameAscIdAsc(
        storeId: UUID,
        lifecycle: MenuLifecycle,
    ): List<MenuEntity>
}

internal interface MenuChildCount {
    val menuId: UUID
    val total: Long
}

internal interface MenuOptionJpaRepository : JpaRepository<MenuOptionEntity, UUID> {
    fun countByIdIn(ids: Collection<UUID>): Long

    fun findAllByMenuIdAndLifecycle(
        menuId: UUID,
        lifecycle: MenuLifecycle,
    ): List<MenuOptionEntity>

    @Query(
        "SELECT child.menuId AS menuId, count(child) AS total FROM MenuOptionEntity child WHERE child.menuId IN :menuIds AND child.lifecycle = :lifecycle GROUP BY child.menuId",
    )
    fun countByMenuIdsAndLifecycle(
        menuIds: Collection<UUID>,
        lifecycle: MenuLifecycle,
    ): List<MenuChildCount>

    @Query(
        """
        SELECT count(child) FROM MenuOptionEntity child JOIN MenuEntity menu ON child.menuId = menu.id
         WHERE menu.storeId = :storeId AND menu.lifecycle = :lifecycle AND child.lifecycle = :lifecycle
           AND (:excludedMenuId IS NULL OR menu.id <> :excludedMenuId)
    """,
    )
    fun countForStore(
        storeId: UUID,
        lifecycle: MenuLifecycle,
        excludedMenuId: UUID?,
    ): Long

    fun findAllByMenuIdIn(menuIds: Collection<UUID>): List<MenuOptionEntity>

    fun findAllByMenuId(menuId: UUID): List<MenuOptionEntity>
}

internal interface MenuConfigurationJpaRepository : JpaRepository<MenuConfigurationEntity, UUID> {
    fun countByIdIn(ids: Collection<UUID>): Long

    fun findAllByMenuIdAndLifecycle(
        menuId: UUID,
        lifecycle: MenuLifecycle,
    ): List<MenuConfigurationEntity>

    @Query(
        "SELECT child.menuId AS menuId, count(child) AS total FROM MenuConfigurationEntity child WHERE child.menuId IN :menuIds AND child.lifecycle = :lifecycle GROUP BY child.menuId",
    )
    fun countByMenuIdsAndLifecycle(
        menuIds: Collection<UUID>,
        lifecycle: MenuLifecycle,
    ): List<MenuChildCount>

    fun findAllByMenuIdIn(menuIds: Collection<UUID>): List<MenuConfigurationEntity>

    fun findAllByMenuId(menuId: UUID): List<MenuConfigurationEntity>
}
