package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.merchant.api.MenuCatalogLifecycle
import io.github.kdh949.beanflow.merchant.api.MenuCatalogOperations
import io.github.kdh949.beanflow.shared.api.CursorSortAdapter
import io.github.kdh949.beanflow.shared.api.OperatorActor
import io.github.kdh949.beanflow.shared.api.SignedCursorCodec
import io.github.kdh949.beanflow.shared.api.SignedCursorScope
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.time.Clock
import java.util.HexFormat
import java.util.UUID

internal data class OperatorMediaMenu(
    val menuId: UUID,
    val name: String,
    val lifecycle: MenuCatalogLifecycle,
)

internal data class OperatorMediaMenuPage(
    val items: List<OperatorMediaMenu>,
    val nextCursor: String?,
)

internal data class OperatorMediaMenuSort(
    val name: String,
    val menuId: UUID,
)

@Service
internal class OperatorMediaMenuDirectory(
    private val authorization: OperatorStoreImageTransaction,
    private val catalog: MenuCatalogOperations,
    private val cursors: SignedCursorCodec,
    private val clock: Clock,
) {
    @Transactional
    fun list(
        actorId: UUID,
        storeId: UUID,
        reason: String,
        lifecycle: MenuCatalogLifecycle,
        cursor: String?,
    ): OperatorMediaMenuPage {
        authorization.authorize(actorId, reason)
        val filter = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("$actorId|$storeId|$lifecycle".toByteArray()))
        val scope = SignedCursorScope("operator-media-menu-directory", filter, SORT)
        val after = cursor?.let { cursors.verify(it, scope).sort }
        val page = catalog.list(storeId, lifecycle, after?.name, after?.menuId, 20)
        val next =
            if (page.nextName != null && page.nextMenuId != null) {
                cursors.issue(scope, OperatorMediaMenuSort(page.nextName, page.nextMenuId), clock.instant().plusSeconds(1800))
            } else {
                null
            }
        return OperatorMediaMenuPage(page.items.map { OperatorMediaMenu(it.menuId, it.name, it.lifecycle) }, next)
    }

    private companion object {
        val SORT =
            object : CursorSortAdapter<OperatorMediaMenuSort> {
                override fun encode(sort: OperatorMediaMenuSort) = listOf(sort.name, sort.menuId.toString())

                override fun decode(values: List<String>): OperatorMediaMenuSort? {
                    if (values.size != 2) return null
                    return try {
                        OperatorMediaMenuSort(values[0], UUID.fromString(values[1]))
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                }
            }
    }
}

@RestController
internal class OperatorMediaMenuDirectoryController(
    private val directory: OperatorMediaMenuDirectory,
) {
    @GetMapping("/api/v1/operations/stores/{storeId}/media-menus")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun list(
        actor: OperatorActor,
        @PathVariable storeId: UUID,
        @RequestHeader("X-Access-Reason") reason: String,
        @RequestParam(defaultValue = "ACTIVE") lifecycle: MenuCatalogLifecycle,
        @RequestParam(required = false) cursor: String?,
    ): ResponseEntity<OperatorMediaMenuPage> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(directory.list(actor.actorId, storeId, reason, lifecycle, cursor))
}
