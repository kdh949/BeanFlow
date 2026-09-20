package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.support.internal.domain.SupportAuthorizationBasis
import io.github.kdh949.beanflow.support.internal.domain.SupportCaseState
import io.github.kdh949.beanflow.support.internal.domain.VerificationSubjectType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.util.UUID

internal val SUPPORT_DIRECT_REQUEST_TTL: Duration = Duration.ofMinutes(15)

internal fun requireDirectAuthorization(basis: SupportAuthorizationBasis) {
    if (basis != SupportAuthorizationBasis.SUPPORT_DIRECT) {
        throw DomainFailure(FailureCode.SUPPORT_ACTION_REQUEST_STALE, "이전 정책의 요청입니다. 같은 상담에서 새 처리 요청을 작성하세요.")
    }
}

internal fun SupportCaseSubjectLinkEntity.verificationSubjectType(): VerificationSubjectType =
    when (subjectType) {
        SupportSubjectType.CUSTOMER -> VerificationSubjectType.CUSTOMER
        SupportSubjectType.STORE -> VerificationSubjectType.STORE
        SupportSubjectType.DELIVERY -> VerificationSubjectType.DELIVERY
        else -> throw DomainFailure(FailureCode.ACCESS_DENIED, "지원 대상 연결이 필요합니다.")
    }

@Service
internal class SupportDirectAuthorization(
    private val cases: SupportCaseJpaRepository,
    private val links: SupportCaseSubjectLinkJpaRepository,
    private val permissions: OperatorPermissionAuthorization,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun requireSubject(
        actorId: UUID,
        caseId: UUID,
        subjectLinkId: UUID,
    ): SupportCaseSubjectLinkEntity {
        permissions.requireActive(actorId, OperatorPermission.SUPPORT_CASE_READ)
        val supportCase =
            cases.findLockedById(caseId)
                ?: throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "SupportCase was not found")
        if (supportCase.currentAssigneeId != actorId ||
            supportCase.state !in setOf(SupportCaseState.OPEN, SupportCaseState.IN_PROGRESS, SupportCaseState.WAITING)
        ) {
            denied()
        }
        val link = links.findByIdAndSupportCaseId(subjectLinkId, caseId)?.takeIf { it.unlinkedAt == null } ?: denied()
        link.verificationSubjectType()
        return link
    }

    private fun denied(): Nothing = throw DomainFailure(FailureCode.ACCESS_DENIED, "현재 담당 상담과 활성 대상 연결이 필요합니다.")
}
