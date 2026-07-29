package io.github.kdh949.beanflow.architecture

import io.github.kdh949.beanflow.BeanflowApplication
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

class ModularityTests {

	@Test
	fun `module dependencies respect public api boundaries`() {
		ApplicationModules.of(BeanflowApplication::class.java).verify()
	}
}
