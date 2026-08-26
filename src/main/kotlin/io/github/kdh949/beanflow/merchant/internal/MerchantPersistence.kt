package io.github.kdh949.beanflow.merchant.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import jakarta.persistence.Version
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
    val acceptingOrders: Boolean,
    @Column(name = "pickup_enabled", nullable = false)
    val pickupEnabled: Boolean,
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
    val name: String,
    @Column(name = "base_price_krw", nullable = false)
    val basePriceKrw: Long,
    @Column(nullable = false)
    val available: Boolean,
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
}

@Entity
@Table(name = "merchant_menu_option")
internal class MenuOptionEntity(
    @Id
    val id: UUID,
    @Column(name = "menu_id", nullable = false)
    val menuId: UUID,
    @Column(nullable = false)
    val name: String,
    @Column(name = "additional_price_krw", nullable = false)
    val additionalPriceKrw: Long,
    @Column(nullable = false)
    val available: Boolean,
)

@Entity
@Table(name = "merchant_menu_configuration")
internal class MenuConfigurationEntity(
    @Id
    val id: UUID,
    @Column(name = "menu_id", nullable = false)
    val menuId: UUID,
    @Column(name = "normalized_option_key", nullable = false)
    val normalizedOptionKey: String,
    @Column(nullable = false)
    val available: Boolean,
    @Version
    var version: Long = 0,
)

@Entity
@Table(name = "merchant_menu_configuration_requirement")
internal class MenuConfigurationRequirementEntity(
    @Id
    val id: UUID,
    @Column(name = "menu_configuration_id", nullable = false)
    val menuConfigurationId: UUID,
    @Column(name = "sellable_unit_id", nullable = false)
    val sellableUnitId: UUID,
    @Column(name = "quantity_per_line_unit", nullable = false)
    val quantityPerLineUnit: Long,
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
}

internal interface MenuOptionJpaRepository : JpaRepository<MenuOptionEntity, UUID> {
    fun findAllByMenuIdIn(menuIds: Collection<UUID>): List<MenuOptionEntity>
}

internal interface MenuConfigurationJpaRepository : JpaRepository<MenuConfigurationEntity, UUID> {
    fun findAllByMenuIdIn(menuIds: Collection<UUID>): List<MenuConfigurationEntity>
}

internal interface MenuConfigurationRequirementJpaRepository : JpaRepository<MenuConfigurationRequirementEntity, UUID> {
    fun findAllByMenuConfigurationIdIn(menuConfigurationIds: Collection<UUID>): List<MenuConfigurationRequirementEntity>
}
