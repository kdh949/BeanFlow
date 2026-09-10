package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.eventing.api.OrderCancelledV1
import io.github.kdh949.beanflow.eventing.api.OrderReadyV1
import io.github.kdh949.beanflow.eventing.api.OrderRejectedV1
import org.springframework.context.ApplicationContext
import org.springframework.context.event.ApplicationListenerMethodAdapter
import org.springframework.core.MethodIntrospector
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.util.ClassUtils

/** Uses Spring's registered bean metadata and default listener ID algorithm, including legacy named methods. */
@Component
internal class ManualPublicationTargetRegistry(
    private val context: ApplicationContext,
    private val compensation: CompensationPublicationTargetRegistry,
) {
    private val bindings by lazy {

        context.beanDefinitionNames
            .flatMap { beanName ->
                val type =
                    context
                        .getType(
                            beanName,
                            false,
                        )?.let(ClassUtils::getUserClass) ?: return@flatMap emptyList()
                MethodIntrospector
                    .selectMethods(
                        type,
                        MethodIntrospector.MetadataLookup<TransactionalEventListener> { method ->
                            AnnotatedElementUtils.findMergedAnnotation(
                                method,
                                TransactionalEventListener::class.java,
                            )
                        },
                    ).filter {
                        (
                            method,
                            annotation,
                        ),
                        ->
                        annotation.phase == TransactionPhase.AFTER_COMMIT && method.parameterCount == 1
                    }.map {
                        (
                            method,
                            _,
                        ),
                        ->
                        ApplicationListenerMethodAdapter(
                            beanName,
                            type,
                            method,
                        ).listenerId to method.parameterTypes.single().name
                    }
            }.toSet()
    }

    fun supports(
        eventType: String,
        listenerId: String,
    ): Boolean =
        !listenerId.startsWith("beanflow.analytics.") && (listenerId to eventType) in bindings &&
            (
                eventType !in setOf(OrderRejectedV1::class.java.name, OrderCancelledV1::class.java.name) ||
                    compensation.find(eventType, listenerId) != null
            )

    /** ADR-125: DB의 source unique constraint와 실제 동시 replay 테스트로 검증한 대상만 허용한다. */
    fun supportsUnknownReplay(
        eventType: String,
        listenerId: String,
    ): Boolean =
        supports(eventType, listenerId) && eventType == OrderReadyV1::class.java.name &&
            listenerId ==
            "io.github.kdh949.beanflow.notification.internal.OrderReadyNotificationListener.on(${OrderReadyV1::class.java.name})"
}
