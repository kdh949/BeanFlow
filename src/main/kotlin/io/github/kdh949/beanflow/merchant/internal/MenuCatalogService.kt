package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.merchant.api.ArchiveMenuCatalogCommand
import io.github.kdh949.beanflow.merchant.api.CreateMenuCatalogCommand
import io.github.kdh949.beanflow.merchant.api.MenuCatalogLifecycle
import io.github.kdh949.beanflow.merchant.api.MenuCatalogMutation
import io.github.kdh949.beanflow.merchant.api.MenuCatalogOperations
import io.github.kdh949.beanflow.merchant.api.MenuCatalogPage
import io.github.kdh949.beanflow.merchant.api.MenuCatalogSummary
import io.github.kdh949.beanflow.merchant.api.MenuConfigurationTradeContent
import io.github.kdh949.beanflow.merchant.api.MenuOptionTradeContent
import io.github.kdh949.beanflow.merchant.api.MenuSellableRequirement
import io.github.kdh949.beanflow.merchant.api.MenuTradeContent
import io.github.kdh949.beanflow.merchant.api.MenuTradeDefinition
import io.github.kdh949.beanflow.merchant.api.ReplaceMenuTradeContentCommand
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.github.kdh949.beanflow.shared.api.ReplaceStoreSearchTermsCommand
import io.github.kdh949.beanflow.shared.api.StoreSearchIndexOperations
import io.github.kdh949.beanflow.shared.api.StoreSearchTermEntry
import io.github.kdh949.beanflow.shared.api.StoreSearchTermKind
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.util.HexFormat
import java.util.UUID

@Service
internal class MenuCatalogService(
    private val stores: StoreJpaRepository,
    private val menus: MenuJpaRepository,
    private val options: MenuOptionJpaRepository,
    private val configurations: MenuConfigurationJpaRepository,
    private val requirements: MenuConfigurationRequirementJpaRepository,
    private val commands: MenuCatalogCommandRepository,
    private val searchIndex: StoreSearchIndexOperations,
    private val identifiers: IdentifierSource,
    private val objectMapper: ObjectMapper,
) : MenuCatalogOperations {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun list(
        storeId: UUID,
        lifecycle: MenuCatalogLifecycle,
        afterName: String?,
        afterMenuId: UUID?,
        limit: Int,
    ): MenuCatalogPage {
        requireStoreForRead(storeId)
        if (limit !in 1..MAX_PAGE_SIZE || (afterName == null) != (afterMenuId == null)) {
            invalid("Menu catalogue page boundary is invalid")
        }
        val rows =
            menus.findCatalogPage(
                storeId,
                lifecycle.internal(),
                afterName,
                afterMenuId,
                PageRequest.of(0, limit + 1),
            )
        val page = rows.take(limit)
        val optionCounts =
            options.findAllByMenuIdIn(page.map(MenuEntity::id))
                .filter { it.lifecycle == it.menuLifecycle(page) }
                .groupingBy(MenuOptionEntity::menuId)
                .eachCount()
        val configurationCounts =
            configurations.findAllByMenuIdIn(page.map(MenuEntity::id))
                .filter { configuration -> page.any { it.id == configuration.menuId && it.lifecycle == configuration.lifecycle } }
                .groupingBy(MenuConfigurationEntity::menuId)
                .eachCount()
        val last = page.lastOrNull()
        return MenuCatalogPage(
            items =
                page.map { menu ->
                    MenuCatalogSummary(
                        menu.id,
                        menu.name,
                        menu.basePriceKrw,
                        menu.available,
                        menu.lifecycle.api(),
                        optionCounts[menu.id] ?: 0,
                        configurationCounts[menu.id] ?: 0,
                        menu.tradeVersion,
                        menu.tradeUpdatedAt,
                    )
                },
            nextName = if (rows.size > limit) last?.name else null,
            nextMenuId = if (rows.size > limit) last?.id else null,
        )
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun find(
        storeId: UUID,
        menuId: UUID,
    ): MenuTradeContent {
        requireStoreForRead(storeId)
        return loadMenu(storeId, menuId).snapshot()
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun create(command: CreateMenuCatalogCommand): MenuCatalogMutation {
        validateKey(command.idempotencyKey)
        val definition = normalize(command.definition)
        if (definition.menuId != command.definition.menuId) invalid("Menu id is invalid")
        requireStoreForWrite(command.storeId)
        val hash = payloadHash(CREATE, command.storeId, definition, null)
        replay(command.actorId, command.idempotencyKey, hash)?.let {
            return MenuCatalogMutation(it, null, changed = false, replayed = true)
        }
        if (menus.existsById(definition.menuId)) conflict("Menu id is already in use")
        requireNewChildIds(definition, emptySet(), emptySet())
        requireStoreBounds(command.storeId, addingMenu = true, replacingMenuId = null, desiredOptions = definition.options.size)

        val menu =
            MenuEntity(
                id = definition.menuId,
                storeId = command.storeId,
                name = definition.name,
                basePriceKrw = definition.basePriceKrw,
                available = definition.available,
                tradeUpdatedAt = command.now,
            )
        try {
            menus.save(menu)
            persistNewChildren(menu.id, definition, command.now)
            menus.flush()
        } catch (failure: DataIntegrityViolationException) {
            throw DomainFailure(FailureCode.RESOURCE_STATE_CONFLICT, "Menu catalogue identifiers conflict").also {
                it.initCause(failure)
            }
        }
        replaceMenuSearchTerms(command.storeId)
        val content = loadMenu(command.storeId, menu.id).snapshot()
        record(command.actorId, command.idempotencyKey, CREATE, hash, command.storeId, menu.id, content, command.now)
        return MenuCatalogMutation(content, null, changed = true, replayed = false)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun replace(command: ReplaceMenuTradeContentCommand): MenuCatalogMutation {
        validateKey(command.idempotencyKey)
        if (command.expectedVersion < 0) invalid("expectedVersion must be zero or greater")
        val definition = normalize(command.definition)
        if (definition.menuId != command.menuId) invalid("Body menuId must match the path menuId")
        requireStoreForWrite(command.storeId)
        val hash = payloadHash(REPLACE, command.storeId, definition, command.expectedVersion)
        replay(command.actorId, command.idempotencyKey, hash)?.let {
            return MenuCatalogMutation(it, it, changed = false, replayed = true)
        }
        val aggregate = loadMenu(command.storeId, command.menuId)
        val menu = aggregate.menu
        if (menu.lifecycle != MenuLifecycle.ACTIVE) conflict("An archived Menu cannot be replaced")
        if (menu.tradeVersion != command.expectedVersion) stale()
        val previous = aggregate.snapshot()
        if (previous.sameTradeMeaning(definition)) {
            record(command.actorId, command.idempotencyKey, REPLACE, hash, command.storeId, menu.id, previous, command.now)
            return MenuCatalogMutation(previous, previous, changed = false, replayed = false)
        }

        val activeOptions = aggregate.options.filter { it.lifecycle == MenuLifecycle.ACTIVE }
        val activeConfigurations = aggregate.configurations.filter { it.lifecycle == MenuLifecycle.ACTIVE }
        requireNewChildIds(definition, activeOptions.mapTo(mutableSetOf(), MenuOptionEntity::id), activeConfigurations.mapTo(mutableSetOf(), MenuConfigurationEntity::id))
        requireStoreBounds(command.storeId, addingMenu = false, replacingMenuId = menu.id, desiredOptions = definition.options.size)
        val searchMeaningChanged =
            menu.name != definition.name || menu.available != definition.available
        replaceOptions(menu.id, activeOptions, definition.options, command.now)
        replaceConfigurations(menu.id, activeConfigurations, definition.configurations, command.now)
        menu.replaceTradeContent(definition.name, definition.basePriceKrw, definition.available, command.now)
        menus.flush()
        if (searchMeaningChanged) replaceMenuSearchTerms(command.storeId)
        val content = loadMenu(command.storeId, menu.id).snapshot()
        record(command.actorId, command.idempotencyKey, REPLACE, hash, command.storeId, menu.id, content, command.now)
        return MenuCatalogMutation(content, previous, changed = true, replayed = false)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun archive(command: ArchiveMenuCatalogCommand): MenuCatalogMutation {
        validateKey(command.idempotencyKey)
        if (command.expectedVersion < 0) invalid("expectedVersion must be zero or greater")
        requireStoreForWrite(command.storeId)
        val hash = payloadHash(ARCHIVE, command.storeId, command.menuId, command.expectedVersion)
        replay(command.actorId, command.idempotencyKey, hash)?.let {
            return MenuCatalogMutation(it, it, changed = false, replayed = true)
        }
        val aggregate = loadMenu(command.storeId, command.menuId)
        if (aggregate.menu.tradeVersion != command.expectedVersion) stale()
        if (aggregate.menu.lifecycle != MenuLifecycle.ACTIVE) conflict("An archived Menu cannot be archived again")
        val previous = aggregate.snapshot()
        aggregate.options.filter { it.lifecycle == MenuLifecycle.ACTIVE }.forEach {
            it.lifecycle = MenuLifecycle.ARCHIVED
            it.archivedAt = command.now
        }
        aggregate.configurations.filter { it.lifecycle == MenuLifecycle.ACTIVE }.forEach {
            it.lifecycle = MenuLifecycle.ARCHIVED
            it.archivedAt = command.now
        }
        aggregate.menu.archive(command.now)
        menus.flush()
        replaceMenuSearchTerms(command.storeId)
        val content = loadMenu(command.storeId, command.menuId).snapshot()
        record(command.actorId, command.idempotencyKey, ARCHIVE, hash, command.storeId, command.menuId, content, command.now)
        return MenuCatalogMutation(content, previous, changed = true, replayed = false)
    }

    private fun requireStoreForRead(storeId: UUID) {
        stores.findByIdForShare(storeId) ?: notFound()
    }

    private fun requireStoreForWrite(storeId: UUID) {
        stores.findByIdForUpdate(storeId) ?: notFound()
    }

    private fun loadMenu(
        storeId: UUID,
        menuId: UUID,
    ): LoadedMenuCatalog {
        val menu = menus.findByIdAndStoreId(menuId, storeId) ?: notFound()
        val menuOptions = options.findAllByMenuId(menuId)
        val menuConfigurations = configurations.findAllByMenuId(menuId)
        val byConfiguration =
            requirements.findAllByMenuConfigurationIdIn(menuConfigurations.map(MenuConfigurationEntity::id))
                .groupBy(MenuConfigurationRequirementEntity::menuConfigurationId)
        return LoadedMenuCatalog(menu, menuOptions, menuConfigurations, byConfiguration)
    }

    private fun persistNewChildren(
        menuId: UUID,
        definition: MenuTradeDefinition,
        now: java.time.Instant,
    ) {
        options.saveAll(
            definition.options.map { MenuOptionEntity(it.optionId, menuId, it.name, it.additionalPriceKrw, it.available) },
        )
        configurations.saveAll(
            definition.configurations.map {
                MenuConfigurationEntity(it.configurationId, menuId, optionKey(it.selectedOptionIds), it.available)
            },
        )
        requirements.saveAll(
            definition.configurations.flatMap { configuration ->
                configuration.requirements.map {
                    MenuConfigurationRequirementEntity(
                        identifiers.next(),
                        configuration.configurationId,
                        it.sellableUnitId,
                        it.quantityPerLineUnit,
                    )
                }
            },
        )
    }

    private fun replaceOptions(
        menuId: UUID,
        current: List<MenuOptionEntity>,
        desired: List<MenuOptionTradeContent>,
        now: java.time.Instant,
    ) {
        val desiredById = desired.associateBy(MenuOptionTradeContent::optionId)
        current.forEach { option ->
            val replacement = desiredById[option.id]
            if (replacement == null) {
                option.lifecycle = MenuLifecycle.ARCHIVED
                option.archivedAt = now
            } else {
                option.name = replacement.name
                option.additionalPriceKrw = replacement.additionalPriceKrw
                option.available = replacement.available
            }
        }
        options.saveAll(
            desired.filter { replacement -> current.none { it.id == replacement.optionId } }
                .map { MenuOptionEntity(it.optionId, menuId, it.name, it.additionalPriceKrw, it.available) },
        )
    }

    private fun replaceConfigurations(
        menuId: UUID,
        current: List<MenuConfigurationEntity>,
        desired: List<MenuConfigurationTradeContent>,
        now: java.time.Instant,
    ) {
        val desiredById = desired.associateBy(MenuConfigurationTradeContent::configurationId)
        // Temporarily leaving the active partial index lets two existing configuration IDs swap
        // their Option sets. This transition is transaction-local and is never externally visible.
        current.filter { it.id in desiredById }.forEach {
            it.lifecycle = MenuLifecycle.ARCHIVED
            it.archivedAt = now
        }
        configurations.flush()
        current.forEach { configuration ->
            val replacement = desiredById[configuration.id]
            if (replacement == null) {
                configuration.lifecycle = MenuLifecycle.ARCHIVED
                configuration.archivedAt = now
            } else {
                configuration.lifecycle = MenuLifecycle.ACTIVE
                configuration.archivedAt = null
                configuration.normalizedOptionKey = optionKey(replacement.selectedOptionIds)
                configuration.available = replacement.available
                requirements.deleteAll(
                    requirements.findAllByMenuConfigurationIdIn(listOf(configuration.id)),
                )
            }
        }
        configurations.saveAll(
            desired.filter { replacement -> current.none { it.id == replacement.configurationId } }
                .map {
                    MenuConfigurationEntity(it.configurationId, menuId, optionKey(it.selectedOptionIds), it.available)
                },
        )
        configurations.flush()
        requirements.saveAll(
            desired.flatMap { configuration ->
                configuration.requirements.map {
                    MenuConfigurationRequirementEntity(
                        identifiers.next(),
                        configuration.configurationId,
                        it.sellableUnitId,
                        it.quantityPerLineUnit,
                    )
                }
            },
        )
    }

    private fun requireNewChildIds(
        definition: MenuTradeDefinition,
        allowedOptionIds: Set<UUID>,
        allowedConfigurationIds: Set<UUID>,
    ) {
        if (definition.options.any { it.optionId !in allowedOptionIds && options.existsById(it.optionId) }) {
            conflict("Menu option id is already in use")
        }
        if (definition.configurations.any {
                it.configurationId !in allowedConfigurationIds && configurations.existsById(it.configurationId)
            }
        ) {
            conflict("Menu configuration id is already in use")
        }
    }

    private fun requireStoreBounds(
        storeId: UUID,
        addingMenu: Boolean,
        replacingMenuId: UUID?,
        desiredOptions: Int,
    ) {
        val activeMenus = menus.findAllByStoreIdAndLifecycleOrderByNameAscIdAsc(storeId, MenuLifecycle.ACTIVE)
        if (addingMenu && activeMenus.size >= MAX_STORE_MENUS) invalid("A Store may have at most $MAX_STORE_MENUS active Menus")
        val activeOptionCount =
            options.findAllByMenuIdIn(activeMenus.map(MenuEntity::id)).count {
                it.lifecycle == MenuLifecycle.ACTIVE && it.menuId != replacingMenuId
            }
        if (activeOptionCount + desiredOptions > MAX_STORE_MENU_OPTIONS) {
            invalid("A Store may have at most $MAX_STORE_MENU_OPTIONS active Menu options")
        }
    }

    private fun replaceMenuSearchTerms(storeId: UUID) {
        val terms =
            menus.findAllByStoreIdAndLifecycleOrderByNameAscIdAsc(storeId, MenuLifecycle.ACTIVE)
                .filter(MenuEntity::available)
                .map { StoreSearchTermEntry(StoreSearchTermKind.MENU_NAME, it.name, it.id) }
        searchIndex.replaceStoreTerms(
            ReplaceStoreSearchTermsCommand(storeId, setOf(StoreSearchTermKind.MENU_NAME), terms),
        )
    }

    private fun replay(
        actorId: UUID,
        key: String,
        hash: String,
    ): MenuTradeContent? =
        commands.find(actorId, key)?.let {
            if (it.payloadHash != hash) {
                throw DomainFailure(FailureCode.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key was reused with another Menu command")
            }
            objectMapper.readValue(it.responseJson, MenuTradeContent::class.java)
        }

    private fun record(
        actorId: UUID,
        key: String,
        operation: String,
        hash: String,
        storeId: UUID,
        menuId: UUID,
        content: MenuTradeContent,
        now: java.time.Instant,
    ) {
        commands.insert(
            identifiers.next(),
            actorId,
            operation,
            key,
            hash,
            storeId,
            menuId,
            objectMapper.writeValueAsString(content),
            now,
        )
    }

    private fun normalize(raw: MenuTradeDefinition): MenuTradeDefinition {
        val name = validName(raw.name, "Menu name")
        if (raw.basePriceKrw < 0) invalid("Menu base price must not be negative")
        if (raw.options.size > MAX_OPTIONS_PER_MENU) invalid("A Menu may have at most $MAX_OPTIONS_PER_MENU active options")
        if (raw.configurations.size > MAX_CONFIGURATIONS_PER_MENU) {
            invalid("A Menu may have at most $MAX_CONFIGURATIONS_PER_MENU active configurations")
        }
        if (raw.options.map(MenuOptionTradeContent::optionId).distinct().size != raw.options.size) {
            invalid("Menu option IDs must be unique")
        }
        if (raw.configurations.map(MenuConfigurationTradeContent::configurationId).distinct().size != raw.configurations.size) {
            invalid("Menu configuration IDs must be unique")
        }
        val normalizedOptions =
            raw.options.map {
                if (it.additionalPriceKrw < 0) invalid("Menu option price must not be negative")
                it.copy(name = validName(it.name, "Menu option name"))
            }.sortedBy { it.optionId.toString() }
        val optionIds = normalizedOptions.mapTo(mutableSetOf(), MenuOptionTradeContent::optionId)
        val normalizedConfigurations =
            raw.configurations.map { configuration ->
                if (configuration.selectedOptionIds.distinct().size != configuration.selectedOptionIds.size) {
                    invalid("Selected Menu option IDs must be unique")
                }
                if (!optionIds.containsAll(configuration.selectedOptionIds)) {
                    conflict("An active configuration must reference active options of the same Menu")
                }
                if (configuration.requirements.isEmpty()) invalid("A Menu configuration requires at least one sellable unit")
                if (configuration.requirements.size > MAX_REQUIREMENTS_PER_CONFIGURATION) {
                    invalid("A Menu configuration may have at most $MAX_REQUIREMENTS_PER_CONFIGURATION requirements")
                }
                if (configuration.requirements.map(MenuSellableRequirement::sellableUnitId).distinct().size !=
                    configuration.requirements.size
                ) {
                    invalid("Sellable-unit requirements must be unique within a configuration")
                }
                if (configuration.requirements.any { it.quantityPerLineUnit <= 0 }) {
                    invalid("Sellable-unit requirement quantity must be positive")
                }
                configuration.copy(
                    selectedOptionIds = configuration.selectedOptionIds.distinct().sortedBy(UUID::toString),
                    requirements = configuration.requirements.sortedBy { it.sellableUnitId.toString() },
                )
            }.sortedBy { it.configurationId.toString() }
        if (normalizedConfigurations.map { optionKey(it.selectedOptionIds) }.distinct().size != normalizedConfigurations.size) {
            invalid("Menu configurations must have unique Option sets")
        }
        if (raw.available && normalizedConfigurations.isEmpty()) {
            invalid("An available Menu requires at least one active configuration")
        }
        return raw.copy(name = name, options = normalizedOptions, configurations = normalizedConfigurations)
    }

    private fun validName(
        raw: String,
        field: String,
    ): String {
        val normalized = raw.trim().replace(Regex("\\s+"), " ")
        if (normalized.isEmpty() || normalized.length > MAX_NAME_LENGTH || normalized.any { it.isISOControl() }) {
            invalid("$field must contain 1 to $MAX_NAME_LENGTH non-control characters")
        }
        return normalized
    }

    private fun validateKey(key: String) {
        if (key.length !in 8..128 || key != key.trim() || key.any { it.isISOControl() }) {
            invalid("Idempotency-Key must contain 8 to 128 non-control characters without outer whitespace")
        }
    }

    private fun payloadHash(
        operation: String,
        storeId: UUID,
        value: Any,
        expectedVersion: Long?,
    ): String = sha256(objectMapper.writeValueAsString(listOf(operation, storeId, value, expectedVersion)))

    private fun sha256(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)))

    private fun optionKey(ids: List<UUID>): String = ids.sortedBy(UUID::toString).joinToString(",")

    private fun invalid(message: String): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, message)

    private fun conflict(message: String): Nothing = throw DomainFailure(FailureCode.RESOURCE_STATE_CONFLICT, message)

    private fun stale(): Nothing = throw DomainFailure(FailureCode.MERCHANT_CONTENT_STALE, "Menu trade version is stale")

    private fun notFound(): Nothing = throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Menu or Store was not found")

    private companion object {
        const val CREATE = "CREATE_MENU_V1"
        const val REPLACE = "REPLACE_MENU_TRADE_CONTENT_V1"
        const val ARCHIVE = "ARCHIVE_MENU_V1"
        const val MAX_PAGE_SIZE = 50
        const val MAX_OPTIONS_PER_MENU = 100
        const val MAX_CONFIGURATIONS_PER_MENU = 500
        const val MAX_REQUIREMENTS_PER_CONFIGURATION = 50
        const val MAX_NAME_LENGTH = 200
    }
}

private data class LoadedMenuCatalog(
    val menu: MenuEntity,
    val options: List<MenuOptionEntity>,
    val configurations: List<MenuConfigurationEntity>,
    val requirementsByConfiguration: Map<UUID, List<MenuConfigurationRequirementEntity>>,
) {
    fun snapshot(): MenuTradeContent {
        val lifecycle = menu.lifecycle
        return MenuTradeContent(
            menuId = menu.id,
            name = menu.name,
            basePriceKrw = menu.basePriceKrw,
            available = menu.available,
            lifecycle = lifecycle.api(),
            options =
                options.filter { it.lifecycle == lifecycle }
                    .sortedBy { it.id.toString() }
                    .map { MenuOptionTradeContent(it.id, it.name, it.additionalPriceKrw, it.available) },
            configurations =
                configurations.filter { it.lifecycle == lifecycle }
                    .sortedBy { it.id.toString() }
                    .map { configuration ->
                        MenuConfigurationTradeContent(
                            configuration.id,
                            if (configuration.normalizedOptionKey.isEmpty()) {
                                emptyList()
                            } else {
                                configuration.normalizedOptionKey.split(',').map(UUID::fromString)
                            },
                            configuration.available,
                            requirementsByConfiguration[configuration.id].orEmpty()
                                .sortedBy { it.sellableUnitId.toString() }
                                .map { MenuSellableRequirement(it.sellableUnitId, it.quantityPerLineUnit) },
                        )
                    },
            version = menu.tradeVersion,
            updatedAt = menu.tradeUpdatedAt,
        )
    }
}

private fun MenuTradeContent.sameTradeMeaning(definition: MenuTradeDefinition): Boolean =
    menuId == definition.menuId &&
        name == definition.name &&
        basePriceKrw == definition.basePriceKrw &&
        available == definition.available &&
        lifecycle == MenuCatalogLifecycle.ACTIVE &&
        options == definition.options &&
        configurations == definition.configurations

private fun MenuLifecycle.api(): MenuCatalogLifecycle = MenuCatalogLifecycle.valueOf(name)

private fun MenuCatalogLifecycle.internal(): MenuLifecycle = MenuLifecycle.valueOf(name)

private fun MenuOptionEntity.menuLifecycle(menus: List<MenuEntity>): MenuLifecycle? =
    menus.firstOrNull { it.id == menuId }?.lifecycle

@Component
internal class MenuCatalogCommandRetentionWorker(
    private val cleanup: MenuCatalogCommandRetentionCleanup,
    private val clock: Clock,
    @Value("\${beanflow.menu-catalog-command.retention.batch-size:100}")
    private val batchSize: Int,
) {
    init {
        require(batchSize in 1..1_000) { "Menu catalogue command cleanup batch size is invalid" }
    }

    @Scheduled(
        fixedDelayString = "\${beanflow.menu-catalog-command.retention.fixed-delay-ms:3600000}",
        initialDelayString = "\${beanflow.menu-catalog-command.retention.initial-delay-ms:3600000}",
    )
    fun cleanupExpired() {
        cleanup.deleteExpired(clock.instant(), batchSize)
    }
}
