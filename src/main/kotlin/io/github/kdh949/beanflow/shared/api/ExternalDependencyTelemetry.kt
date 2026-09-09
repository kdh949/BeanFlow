package io.github.kdh949.beanflow.shared.api

enum class ExternalProvider(
    val tagValue: String,
) {
    TOSS("toss"),
    AISTOR("aistor"),
    VAULT("vault"),
}

enum class ExternalDependencyOperation(
    val tagValue: String,
) {
    CONFIRM("confirm"),
    LOOKUP("lookup"),
    CANCEL("cancel"),
    REFUND_LOOKUP("refund-lookup"),
    PUT("put"),
    HEAD("head"),
    PRESIGN("presign"),
    DELETE("delete"),
    LIST("list"),
    ENCRYPT("encrypt"),
    DECRYPT("decrypt"),
    REWRAP("rewrap"),
    HMAC("hmac"),
    KEY_METADATA("key-metadata"),
}

enum class ExternalDependencyOutcome(
    val tagValue: String,
) {
    SUCCESS("success"),
    DECLINED("declined"),
    UNKNOWN("unknown"),
    FAILURE("failure"),
}

data class ExternalDependencyCall(
    val provider: ExternalProvider,
    val operation: ExternalDependencyOperation,
)

interface ExternalDependencyTelemetry {
    fun <T> observe(
        call: ExternalDependencyCall,
        outcomeOf: (T) -> ExternalDependencyOutcome = { ExternalDependencyOutcome.SUCCESS },
        block: () -> T,
    ): T
}
