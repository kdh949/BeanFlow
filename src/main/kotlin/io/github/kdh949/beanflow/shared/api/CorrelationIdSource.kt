package io.github.kdh949.beanflow.shared.api

fun interface CorrelationIdSource {
	fun currentOrCreate(): String
}
