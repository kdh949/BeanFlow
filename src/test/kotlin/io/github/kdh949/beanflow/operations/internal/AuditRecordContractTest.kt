package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class AuditRecordContractTest {
    @Test
    fun `audit append contract requires a closed category`() {
        val categoryType =
            Class.forName("io.github.kdh949.beanflow.operations.api.AuditCategory")

        assertThat(categoryType.isEnum).isTrue()
        assertThat(AppendAuditRecordCommand::class.java.declaredFields.map { it.name })
            .contains("category")
    }
}
