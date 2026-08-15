package io.github.kdh949.beanflow.shared.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AuthenticationPathRegistryTest {
    private val registry = AuthenticationPathRegistry()

    @Test
    fun `every current and reserved endpoint belongs to exactly one chain`() {
        val expected =
            mapOf(
                "/actuator/health" to AuthenticationChain.PUBLIC,
                "/api/v1/payment-config" to AuthenticationChain.PUBLIC,
                "/api/v1/auth/operations/config" to AuthenticationChain.PUBLIC,
                "/api/v1/operations/me" to AuthenticationChain.OPERATIONS,
                "/api/v1/operations/point-accounts/00000000-0000-0000-0000-000000000001" to AuthenticationChain.OPERATIONS,
                "/api/v1/support/cases" to AuthenticationChain.OPERATIONS,
                "/api/v1/auth/merchant/csrf" to AuthenticationChain.MERCHANT,
                "/api/v1/regions" to AuthenticationChain.MERCHANT,
                "/api/v1/stores/00000000-0000-0000-0000-000000000001/region" to AuthenticationChain.MERCHANT,
                "/api/v1/stores/00000000-0000-0000-0000-000000000001/orders" to AuthenticationChain.MERCHANT,
                "/api/v1/stores/00000000-0000-0000-0000-000000000001/settlements" to AuthenticationChain.MERCHANT,
                "/api/v1/stores/00000000-0000-0000-0000-000000000001/support-order-change-authorizations" to AuthenticationChain.MERCHANT,
                "/api/v1/store-orders/00000000-0000-0000-0000-000000000001" to AuthenticationChain.MERCHANT,
                "/api/v1/settlement-items/00000000-0000-0000-0000-000000000001/disputes" to AuthenticationChain.MERCHANT,
                "/api/v1/payments/00000000-0000-0000-0000-000000000001/refunds" to AuthenticationChain.MERCHANT,
                "/api/v1/auth/customer/csrf" to AuthenticationChain.CUSTOMER,
                "/api/v1/me/orders" to AuthenticationChain.CUSTOMER,
                "/api/v1/me/favorite-stores" to AuthenticationChain.CUSTOMER,
                "/api/v1/orders" to AuthenticationChain.CUSTOMER,
                "/api/v1/payment-methods" to AuthenticationChain.CUSTOMER,
                "/api/v1/payments/00000000-0000-0000-0000-000000000001" to AuthenticationChain.CUSTOMER,
                "/api/v1/payments/00000000-0000-0000-0000-000000000001/confirmations" to AuthenticationChain.CUSTOMER,
                "/api/v1/point-accounts/00000000-0000-0000-0000-000000000001" to AuthenticationChain.CUSTOMER,
                "/api/v1/stores/nearby" to AuthenticationChain.CUSTOMER,
                "/api/v1/stores/search" to AuthenticationChain.CUSTOMER,
                "/api/v1/stores/00000000-0000-0000-0000-000000000001/menus" to AuthenticationChain.CUSTOMER,
                "/api/v1/stores/00000000-0000-0000-0000-000000000001/pickup-slots" to AuthenticationChain.CUSTOMER,
            )

        expected.forEach { (path, chain) ->
            assertThat(registry.classify(path)).describedAs(path).isEqualTo(chain)
        }
    }

    @Test
    fun `unknown api endpoint is not silently assigned to customer`() {
        assertThat(registry.classify("/api/v1/not-registered")).isNull()
    }

    @Test
    fun `registered path patterns never overlap`() {
        assertThat(registry.overlappingPatterns()).isEmpty()
    }
}
