package io.github.kdh949.beanflow.shared.api

import java.util.UUID

fun interface IdentifierSource {
	fun next(): UUID
}
