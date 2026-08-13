package io.github.kdh949.beanflow.architecture

import io.github.kdh949.beanflow.discovery.api.NearbyStoreQueryOperations
import io.github.kdh949.beanflow.discovery.api.StoreCatalogQueryOperations
import io.github.kdh949.beanflow.dispute.internal.SettlementDisputeFilingService
import io.github.kdh949.beanflow.identity.internal.CustomerAccountApplicationService
import io.github.kdh949.beanflow.identity.internal.CustomerSourceIpResolver
import io.github.kdh949.beanflow.identity.internal.MerchantAccountApplicationService
import io.github.kdh949.beanflow.loyalty.api.PointAccountQueryOperations
import io.github.kdh949.beanflow.loyalty.api.PointAdjustmentOperations
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationPolicyOperations
import io.github.kdh949.beanflow.operations.api.OperatorCompensationQueryOperations
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicyQueryOperations
import io.github.kdh949.beanflow.operations.internal.CustomerCancellationRefundReconciliationService
import io.github.kdh949.beanflow.operations.internal.MerchantCredentialAdministrationApplicationService
import io.github.kdh949.beanflow.operations.internal.OperationsSupportInvestigationService
import io.github.kdh949.beanflow.operations.internal.OrdinaryPointAccrualPolicyService
import io.github.kdh949.beanflow.operations.internal.PaymentSetupRepairService
import io.github.kdh949.beanflow.ordering.api.CreateOrderUseCase
import io.github.kdh949.beanflow.ordering.api.ReorderOrderUseCase
import io.github.kdh949.beanflow.ordering.internal.CustomerCancellationService
import io.github.kdh949.beanflow.ordering.internal.CustomerOrderQueryService
import io.github.kdh949.beanflow.ordering.internal.GetOrderService
import io.github.kdh949.beanflow.ordering.internal.OneTimeCheckoutService
import io.github.kdh949.beanflow.ordering.internal.PartialRefundService
import io.github.kdh949.beanflow.ordering.internal.PublicOrderReferenceService
import io.github.kdh949.beanflow.ordering.internal.StoreOrderBoardQueryService
import io.github.kdh949.beanflow.ordering.internal.StoreOrderTransitionService
import io.github.kdh949.beanflow.payment.internal.PaymentMethodApplicationService
import io.github.kdh949.beanflow.payment.internal.PaymentMethodQueryService
import io.github.kdh949.beanflow.settlement.internal.SettlementBatchQueryService
import io.github.kdh949.beanflow.settlement.internal.SettlementItemQueryService
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.github.kdh949.beanflow.support.internal.BreakGlassApplicationService
import io.github.kdh949.beanflow.support.internal.DataAccessGrantApplicationService
import io.github.kdh949.beanflow.support.internal.PostAcceptanceResolutionApplicationService
import io.github.kdh949.beanflow.support.internal.SupportActionEvaluationApplicationService
import io.github.kdh949.beanflow.support.internal.SupportActionRequestApplicationService
import io.github.kdh949.beanflow.support.internal.SupportCaseApplicationService
import io.github.kdh949.beanflow.support.internal.SupportCompensationApplicationService
import io.github.kdh949.beanflow.support.internal.SupportOrderChangeAuthorizationApplicationService
import io.github.kdh949.beanflow.support.internal.SupportOrderChangeExecutionApplicationService
import io.github.kdh949.beanflow.support.internal.SupportProfileChangeApplicationService
import io.github.kdh949.beanflow.support.internal.SupportSubjectSearchApplicationService
import io.github.kdh949.beanflow.support.internal.SupportTimelineApplicationService
import io.github.kdh949.beanflow.support.internal.SupportVerificationApplicationService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.session.web.http.HttpSessionIdResolver
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.nio.file.Path
import java.time.Clock
import kotlin.io.path.readLines

@WebMvcTest
internal class RuntimeOpenApiParityTest(
    @Qualifier("requestMappingHandlerMapping")
    private val handlerMapping: RequestMappingHandlerMapping,
) {
    @MockitoBean
    private lateinit var createOrderUseCase: CreateOrderUseCase

    @MockitoBean
    private lateinit var reorderOrderUseCase: ReorderOrderUseCase

    @MockitoBean
    private lateinit var getOrderService: GetOrderService

    @MockitoBean
    private lateinit var oneTimeCheckoutService: OneTimeCheckoutService

    @MockitoBean
    private lateinit var paymentMethodApplicationService: PaymentMethodApplicationService

    @MockitoBean
    private lateinit var paymentMethodQueryService: PaymentMethodQueryService

    @MockitoBean
    private lateinit var customerCancellationService: CustomerCancellationService

    @MockitoBean
    private lateinit var customerOrderQueryService: CustomerOrderQueryService

    @MockitoBean
    private lateinit var partialRefundService: PartialRefundService

    @MockitoBean
    private lateinit var storeOrderTransitionService: StoreOrderTransitionService

    @MockitoBean
    private lateinit var storeOrderBoardQueryService: StoreOrderBoardQueryService

    @MockitoBean
    private lateinit var publicOrderReferenceService: PublicOrderReferenceService

    @MockitoBean
    private lateinit var paymentSetupRepairService: PaymentSetupRepairService

    @MockitoBean
    private lateinit var customerCancellationRefundReconciliationService: CustomerCancellationRefundReconciliationService

    @MockitoBean
    private lateinit var expiredBenefitRestorationPolicyOperations: ExpiredBenefitRestorationPolicyOperations

    @MockitoBean
    private lateinit var ordinaryPointAccrualPolicyQueryOperations: OrdinaryPointAccrualPolicyQueryOperations

    @MockitoBean
    private lateinit var ordinaryPointAccrualPolicyService: OrdinaryPointAccrualPolicyService

    @MockitoBean
    private lateinit var operatorCompensationQueryOperations: OperatorCompensationQueryOperations

    @MockitoBean
    private lateinit var pointAdjustmentOperations: PointAdjustmentOperations

    @MockitoBean
    private lateinit var pointAccountQueryOperations: PointAccountQueryOperations

    @MockitoBean
    private lateinit var nearbyStoreQueryOperations: NearbyStoreQueryOperations

    @MockitoBean
    private lateinit var storeCatalogQueryOperations: StoreCatalogQueryOperations

    @MockitoBean
    private lateinit var settlementBatchQueryService: SettlementBatchQueryService

    @MockitoBean
    private lateinit var settlementItemQueryService: SettlementItemQueryService

    @MockitoBean
    private lateinit var settlementDisputeFilingService: SettlementDisputeFilingService

    @MockitoBean
    private lateinit var supportCaseApplicationService: SupportCaseApplicationService

    @MockitoBean
    private lateinit var supportTimelineApplicationService: SupportTimelineApplicationService

    @MockitoBean
    private lateinit var supportActionEvaluationApplicationService: SupportActionEvaluationApplicationService

    @MockitoBean
    private lateinit var supportActionRequestApplicationService: SupportActionRequestApplicationService

    @MockitoBean
    private lateinit var supportOrderChangeAuthorizationApplicationService: SupportOrderChangeAuthorizationApplicationService

    @MockitoBean
    private lateinit var supportOrderChangeExecutionApplicationService: SupportOrderChangeExecutionApplicationService

    @MockitoBean
    private lateinit var postAcceptanceResolutionApplicationService: PostAcceptanceResolutionApplicationService

    @MockitoBean
    private lateinit var supportCompensationApplicationService: SupportCompensationApplicationService

    @MockitoBean
    private lateinit var supportProfileChangeApplicationService: SupportProfileChangeApplicationService

    @MockitoBean
    private lateinit var operationsSupportInvestigationService: OperationsSupportInvestigationService

    @MockitoBean
    private lateinit var supportSubjectSearchApplicationService: SupportSubjectSearchApplicationService

    @MockitoBean
    private lateinit var supportVerificationApplicationService: SupportVerificationApplicationService

    @MockitoBean
    private lateinit var dataAccessGrantApplicationService: DataAccessGrantApplicationService

    @MockitoBean
    private lateinit var breakGlassApplicationService: BreakGlassApplicationService

    @MockitoBean
    private lateinit var correlationIdSource: CorrelationIdSource

    @MockitoBean
    private lateinit var identifierSource: IdentifierSource

    @MockitoBean
    private lateinit var clock: Clock

    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    @MockitoBean
    private lateinit var customerAccountApplicationService: CustomerAccountApplicationService

    @MockitoBean
    private lateinit var customerSourceIpResolver: CustomerSourceIpResolver

    @MockitoBean
    private lateinit var merchantAccountApplicationService: MerchantAccountApplicationService

    @MockitoBean
    private lateinit var merchantCredentialAdministrationApplicationService: MerchantCredentialAdministrationApplicationService

    @MockitoBean
    private lateinit var httpSessionIdResolver: HttpSessionIdResolver

    @Test
    fun `runtime OpenAPI operations exactly match public Spring MVC mappings`() {
        val actual = springMvcOperations()
        val documented = OpenApiOperationInventory.load(RUNTIME_OPENAPI)

        assertThat(actual)
            .withFailMessage(
                "Runtime OpenAPI operation parity failed.%nMissing from runtime OpenAPI: %s%n" +
                    "Missing from Spring MVC: %s",
                (actual - documented).sorted(),
                (documented - actual).sorted(),
            ).isEqualTo(documented)
    }

    private fun springMvcOperations(): Set<HttpOperation> =
        handlerMapping.handlerMethods.keys
            .flatMap { mapping ->
                mapping.patternValues.flatMap { path ->
                    if (!normalizePath(path).startsWith(API_PREFIX)) {
                        emptyList()
                    } else {
                        val methods = mapping.methodsCondition.methods
                        check(methods.isNotEmpty()) {
                            "Public mapping must declare an explicit HTTP method: $path"
                        }
                        methods
                            .asSequence()
                            .filterNot { it == RequestMethod.HEAD || it == RequestMethod.OPTIONS }
                            .map { HttpOperation(normalizePath(path), it.name) }
                            .toList()
                    }
                }
            }.toSet()

    private companion object {
        const val API_PREFIX = "/api/v1"
        val RUNTIME_OPENAPI: Path = Path.of("openapi/beanflow-v1-runtime.yaml")
    }
}

private data class HttpOperation(
    val path: String,
    val method: String,
) : Comparable<HttpOperation> {
    override fun compareTo(other: HttpOperation): Int = compareValuesBy(this, other, HttpOperation::path, HttpOperation::method)

    override fun toString(): String = "$method $path"
}

private object OpenApiOperationInventory {
    private val pathLine = Regex("^  (/[^:]*):\\s*$")
    private val methodLine = Regex("^    (get|post|put|patch|delete|head|options):\\s*$")
    private val referenceLine = Regex("^    \\${'$'}ref:\\s*[\\\"]([^\\\"]+)[\\\"]\\s*$")
    private val serverLine = Regex("^  - url:\\s*[\\\"]?([^\\\"#]+)[\\\"]?\\s*$")

    fun load(runtimeFile: Path): Set<HttpOperation> {
        val runtime = parse(runtimeFile)
        check(runtime.serverPrefix == "/api/v1") {
            "Runtime OpenAPI server prefix must be /api/v1: ${runtime.serverPrefix}"
        }
        return runtime.pathItems
            .flatMap { (path, item) ->
                resolveMethods(runtimeFile, item).map { method ->
                    HttpOperation(normalizePath("${runtime.serverPrefix}/$path"), method.uppercase())
                }
            }.toSet()
    }

    private fun resolveMethods(
        ownerFile: Path,
        item: OpenApiPathItem,
    ): Set<String> {
        if (item.reference == null) return item.methods

        check(item.methods.isEmpty()) {
            "A runtime OpenAPI path item cannot mix operation methods with a path-level reference: ${item.reference}"
        }
        val reference = checkNotNull(item.reference)
        val (relativeFile, fragment) =
            reference.split('#', limit = 2).let {
                check(it.size == 2) { "OpenAPI path reference must contain a file and fragment: $reference" }
                it[0] to it[1]
            }
        check(fragment.startsWith("/paths/")) {
            "Only OpenAPI path-item references are supported in the runtime inventory: $reference"
        }
        val referencedPath = decodeJsonPointerToken(fragment.removePrefix("/paths/"))
        val referencedFile = ownerFile.parent.resolve(relativeFile).normalize()
        val referencedDocument = parse(referencedFile)
        val referencedItem =
            checkNotNull(referencedDocument.pathItems[referencedPath]) {
                "OpenAPI path reference does not exist: $reference"
            }
        check(referencedItem.reference == null) {
            "Nested OpenAPI path-item references are not supported: $reference"
        }
        return referencedItem.methods
    }

    private fun parse(file: Path): OpenApiDocument {
        val pathItems = linkedMapOf<String, OpenApiPathItemBuilder>()
        var serverPrefix: String? = null
        var currentPath: String? = null
        var inPaths = false

        file.readLines().forEachIndexed { index, line ->
            serverLine.matchEntire(line)?.let { serverPrefix = it.groupValues[1].trimEnd('/') }
            when {
                line == "paths:" -> {
                    inPaths = true
                    currentPath = null
                }

                inPaths && line.isNotEmpty() && !line.startsWith(' ') -> {
                    inPaths = false
                    currentPath = null
                }

                inPaths -> {
                    pathLine.matchEntire(line)?.let { match ->
                        currentPath = match.groupValues[1]
                        pathItems.getOrPut(checkNotNull(currentPath)) { OpenApiPathItemBuilder() }
                    }
                    methodLine.matchEntire(line)?.let { match ->
                        val path =
                            checkNotNull(currentPath) {
                                "OpenAPI operation has no path at $file:${index + 1}"
                            }
                        pathItems.getValue(path).methods += match.groupValues[1]
                    }
                    referenceLine.matchEntire(line)?.let { match ->
                        val path =
                            checkNotNull(currentPath) {
                                "OpenAPI path reference has no path at $file:${index + 1}"
                            }
                        val builder = pathItems.getValue(path)
                        check(builder.reference == null) {
                            "OpenAPI path has more than one reference at $file:${index + 1}"
                        }
                        builder.reference = match.groupValues[1]
                    }
                }
            }
        }

        check(pathItems.isNotEmpty()) { "OpenAPI document has no paths: $file" }
        pathItems.forEach { (path, item) ->
            check(item.methods.isNotEmpty() || item.reference != null) {
                "OpenAPI path item has neither operations nor a reference: $path"
            }
        }
        return OpenApiDocument(
            serverPrefix = checkNotNull(serverPrefix) { "OpenAPI document has no server URL: $file" },
            pathItems = pathItems.mapValues { (_, value) -> value.build() },
        )
    }

    private fun decodeJsonPointerToken(value: String): String = value.replace("~1", "/").replace("~0", "~")
}

private data class OpenApiDocument(
    val serverPrefix: String,
    val pathItems: Map<String, OpenApiPathItem>,
)

private data class OpenApiPathItem(
    val methods: Set<String>,
    val reference: String?,
)

private class OpenApiPathItemBuilder {
    val methods: MutableSet<String> = linkedSetOf()
    var reference: String? = null

    fun build(): OpenApiPathItem = OpenApiPathItem(methods.toSet(), reference)
}

private val pathVariablePattern = Regex("\\{([A-Za-z_][A-Za-z0-9_]*)(?::[^{}]+)?}")
private val repeatedSlashPattern = Regex("/{2,}")

private fun normalizePath(value: String): String {
    val withLeadingSlash = if (value.startsWith('/')) value else "/$value"
    val normalizedVariables =
        pathVariablePattern.replace(withLeadingSlash) { match -> "{${match.groupValues[1]}}" }
    val normalizedSlashes = repeatedSlashPattern.replace(normalizedVariables, "/")
    return if (normalizedSlashes.length > 1) normalizedSlashes.trimEnd('/') else normalizedSlashes
}
