package io.github.kdh949.beanflow.shared.api

internal class RecordingExternalDependencyTelemetry : ExternalDependencyTelemetry {
    val records = mutableListOf<Record>()

    override fun <T> observe(
        call: ExternalDependencyCall,
        outcomeOf: (T) -> ExternalDependencyOutcome,
        block: () -> T,
    ): T =
        try {
            block().also { result -> records += Record(call, outcomeOf(result)) }
        } catch (failure: Throwable) {
            records += Record(call, ExternalDependencyOutcome.FAILURE)
            throw failure
        }

    data class Record(
        val call: ExternalDependencyCall,
        val outcome: ExternalDependencyOutcome,
    )
}
