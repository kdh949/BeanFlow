package io.github.kdh949.beanflow.operations.internal

import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Repository
internal class AuditRecordAppendRepository(
    private val entityManager: EntityManager,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun appendAllAndFlush(records: List<AuditRecordEntity>) {
        records.forEach(entityManager::persist)
        entityManager.flush()
    }
}
