package io.github.kdh949.beanflow

import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class MissingDatabaseApplicationContextTests {

	private val contextRunner = ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration::class.java))

	@Test
	fun `missing database configuration fails instead of selecting an embedded database`() {
		contextRunner.run { context ->
			require(context.startupFailure != null) {
				"Database-less startup must fail; an embedded or in-memory fallback was selected"
			}
		}
	}
}
