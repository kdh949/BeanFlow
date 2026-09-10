package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.support.internal.domain.CustomerInquiryCategory
import io.github.kdh949.beanflow.support.internal.domain.InquiryMessageAuthor
import io.github.kdh949.beanflow.support.internal.domain.SupportCaseState
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@BeanflowIsolatedSpringContext("committed inquiry claims, audit failure and concurrent writers")
@SpringBootTest(properties = ["beanflow.event-publication.initial-delay-ms=3600000", "beanflow.audit-retention.initial-delay-ms=3600000"])
internal class CustomerInquiryIntegrationTest
    @Autowired
    constructor(
        private val service: CustomerInquiryService,
        private val caseService: SupportCaseApplicationService,
        private val jdbc: JdbcTemplate,
        private val mvc: MockMvc,
        private val cleanup: SupportCaseIdempotencyRetentionCleanup,
    ) {
        private val customer = UUID.randomUUID()
        private val other = UUID.randomUUID()
        private val agent = UUID.randomUUID()
        private val secondAgent = UUID.randomUUID()

        @BeforeEach
        fun reset() {
            dropFailure()
            jdbc.execute(
                "TRUNCATE TABLE support_case, support_customer_inquiry, " +
                    "operations_audit_record, operations_operator_permission_grant CASCADE",
            )
            listOf(agent, secondAgent).forEach { actor ->
                listOf("SUPPORT_CASE_READ", "SUPPORT_CASE_WRITE", "SUPPORT_CASE_ASSIGN").forEach { permission ->
                    jdbc.update(
                        "INSERT INTO operations_operator_permission_grant " +
                            "(actor_id, permission, state, granted_at, version, audit_source_reference) " +
                            "VALUES (?, ?, 'ACTIVE', now(), 1, ?)",
                        actor,
                        permission,
                        "inquiry-test:$actor:$permission",
                    )
                }
            }
        }

        @AfterEach fun dropFailure() {
            jdbc.execute("DROP TRIGGER IF EXISTS inquiry_test_audit_failure ON operations_audit_record")
            jdbc.execute("DROP FUNCTION IF EXISTS reject_inquiry_test_audit()")
        }

        @Test fun `customer owns intake and only public conversation is returned`() {
            val id = create().inquiryId
            assertThat(service.customerList(customer, null).items).hasSize(1)
            assertThat(service.customerList(other, null).items).isEmpty()
            fails(FailureCode.RESOURCE_NOT_FOUND) { service.customerDetail(other, id, null) }
            fails(FailureCode.RESOURCE_NOT_FOUND) { service.customerMessage(other, id, "other-message-key", 0, "다른 고객 문의", "inquiry-test") }
            val claimed = service.claim(agent, id, "claim-inquiry-key", 0, "inquiry-test")
            caseService.appendNote(
                AppendSupportNoteCommand(agent, claimed.caseId, "private-note-key", "INTERNAL_ONLY_NOTE", "상담 내부 검토", "inquiry-test"),
            )
            val staff = service.supportDetail(agent, id, null)
            assertThat(caseService.get(agent, claimed.caseId).customerInquiryId).isEqualTo(id)
            service.supportMessage(
                agent,
                id,
                "public-reply-key",
                1,
                staff.caseVersion!!,
                "환불 진행 상태를 확인하고 있습니다.\n확인 후 다시 안내하겠습니다.",
                "inquiry-test",
            )
            val detail = service.customerDetail(customer, id, null)
            assertThat(detail.inquiry.state).isEqualTo(CustomerInquiryState.OPEN)
            assertThat(detail.messages).hasSize(2)
            assertThat(detail.messages.first().author).isEqualTo(InquiryMessageAuthor.SUPPORT)
            mvc
                .perform(get("/api/v1/me/support-inquiries/$id").with(customerJwt(customer)))
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.caseId").doesNotExist())
                .andExpect(jsonPath("$.customerId").doesNotExist())
                .andDo {
                    assertThat(
                        it.response.contentAsString,
                    ).doesNotContain("INTERNAL_ONLY_NOTE", "assigneeId", "actorId", "subjectLinks", "verification")
                }
        }

        @Test fun `create and reply replay once and conflicting payloads never overwrite`() {
            val first = create()
            assertThat(create()).isEqualTo(first)
            fails(FailureCode.IDEMPOTENCY_KEY_REUSED) {
                service.create(
                    customer,
                    "create-inquiry-key",
                    "변경된 제목",
                    CustomerInquiryCategory.PAYMENT_OR_REFUND,
                    "결제 내역 확인 요청",
                    null,
                    "inquiry-test",
                )
            }
            val reply = service.customerMessage(customer, first.inquiryId, "reply-inquiry-key", 0, "추가로 확인할 내용입니다.", "inquiry-test")
            assertThat(
                service.customerMessage(customer, first.inquiryId, "reply-inquiry-key", 0, "추가로 확인할 내용입니다.", "inquiry-test"),
            ).isEqualTo(reply)
            fails(FailureCode.IDEMPOTENCY_KEY_REUSED) {
                service.customerMessage(customer, first.inquiryId, "reply-inquiry-key", 0, "다른 내용", "inquiry-test")
            }
            assertThat(service.customerDetail(customer, first.inquiryId, null).messages).hasSize(2)
            assertThat(count("support_customer_inquiry")).isEqualTo(1)
            assertThat(count("support_customer_inquiry_command")).isEqualTo(2)
            val commandText =
                jdbc.queryForObject(
                    "SELECT string_agg(row_to_json(c)::text, '') FROM support_customer_inquiry_command c",
                    String::class.java,
                )!!
            assertThat(commandText).doesNotContain("결제 내역", "추가로 확인")
        }

        @Test fun `two agents can create only one assigned case for an inquiry`() {
            val id = create().inquiryId
            val results =
                concurrent(
                    listOf(agent, secondAgent).map { actor ->
                        { runCatching { service.claim(actor, id, "claim-$actor", 0, "inquiry-test") } }
                    },
                )
            assertThat(results.count { it.isSuccess }).isEqualTo(1)
            assertThat(results.single { it.isFailure }.exceptionOrNull()).isInstanceOf(DomainFailure::class.java)
            assertThat(count("support_case")).isEqualTo(1)
            assertThat(count("support_case_subject_link")).isEqualTo(1)
            val winner = results.single { it.isSuccess }.getOrThrow()
            val assigned =
                jdbc.queryForObject(
                    "SELECT current_assignee_id FROM support_case WHERE id = ?",
                    UUID::class.java,
                    winner.caseId,
                )!!
            assertThat(service.claim(assigned, id, "claim-$assigned", 0, "inquiry-test")).isEqualTo(winner)
        }

        @Test fun `current assignment case version and terminal state guard public replies`() {
            val id = create().inquiryId
            val claimed = service.claim(agent, id, "claim-inquiry-key", 0, "inquiry-test")
            val initial = service.supportDetail(agent, id, null)
            fails(FailureCode.ACCESS_DENIED) {
                service.supportMessage(secondAgent, id, "other-agent-reply", 1, initial.caseVersion!!, "확인 중입니다.", "inquiry-test")
            }
            caseService.transition(
                TransitionSupportCaseCommand(
                    agent,
                    claimed.caseId,
                    "start-case-key",
                    SupportCaseState.IN_PROGRESS,
                    initial.caseVersion!!,
                    "상담 진행 시작",
                    "inquiry-test",
                ),
            )
            fails(FailureCode.RESOURCE_STATE_CONFLICT) {
                service.supportMessage(agent, id, "stale-case-reply", 1, initial.caseVersion, "확인 중입니다.", "inquiry-test")
            }
            val active = service.supportDetail(agent, id, null)
            caseService.transition(
                TransitionSupportCaseCommand(
                    agent,
                    claimed.caseId,
                    "resolve-case-key",
                    SupportCaseState.RESOLVED,
                    active.caseVersion!!,
                    "안내 처리 완료",
                    "inquiry-test",
                ),
            )
            assertThat(service.customerDetail(customer, id, null).canReply).isFalse()
            assertThat(service.customerDetail(customer, id, null).inquiry.state).isEqualTo(CustomerInquiryState.RESOLVED)
            fails(
                FailureCode.RESOURCE_STATE_CONFLICT,
            ) { service.customerMessage(customer, id, "terminal-message", 1, "다시 문의합니다.", "inquiry-test") }
            assertThat(service.customerDetail(customer, id, null).messages).hasSize(1)
        }

        @Test fun `audit failure rolls back intake and claim without orphan case`() {
            rejectAudit()
            fails(FailureCode.DEPENDENCY_UNAVAILABLE) { create() }
            assertThat(count("support_customer_inquiry")).isZero()
            assertThat(count("support_customer_inquiry_message")).isZero()
            dropFailure()
            val id = create().inquiryId
            rejectAudit()
            fails(FailureCode.DEPENDENCY_UNAVAILABLE) { service.claim(agent, id, "claim-failure-key", 0, "inquiry-test") }
            assertThat(count("support_case")).isZero()
            assertThat(service.customerDetail(customer, id, null).inquiry.state).isEqualTo(CustomerInquiryState.RECEIVED)
        }

        @Test fun `sensitive input strict request schema and unauthenticated calls are rejected`() {
            fails(FailureCode.INVALID_REQUEST) {
                service.create(
                    customer,
                    "sensitive-inquiry",
                    "정보 확인",
                    CustomerInquiryCategory.OTHER,
                    "password=secret-value",
                    null,
                    "inquiry-test",
                )
            }
            assertThat(count("support_customer_inquiry")).isZero()
            mvc.perform(get("/api/v1/me/support-inquiries")).andExpect(status().isUnauthorized)
            mvc
                .perform(
                    post(
                        "/api/v1/me/support-inquiries",
                    ).with(
                        customerJwt(customer),
                    ).with(
                        csrf().asHeader(),
                    ).header(
                        "Idempotency-Key",
                        "unknown-field-key",
                    ).contentType(
                        MediaType.APPLICATION_JSON,
                    ).content("""{"title":"문의","category":"OTHER","content":"상품 확인 요청","customerId":"$other"}"""),
                ).andExpect(status().isBadRequest)
            jdbc.update("UPDATE operations_operator_permission_grant SET state = 'REVOKED', revoked_at = now() WHERE actor_id = ?", agent)
            fails(FailureCode.ACCESS_DENIED) { service.supportList(agent, true, null) }
        }

        @Test fun `signed cursors page all messages and cannot cross customer scope`() {
            val id = create().inquiryId
            repeat(22) { n -> service.customerMessage(customer, id, "message-page-$n", n.toLong(), "추가 문의 항목 $n", "inquiry-test") }
            val first = service.customerDetail(customer, id, null)
            assertThat(first.messages).hasSize(20)
            val second = service.customerDetail(customer, id, first.nextMessageCursor)
            assertThat(second.messages).hasSize(3)
            assertThat(second.nextMessageCursor).isNull()
            assertThat((first.messages + second.messages).map { it.id }.distinct()).hasSize(23)
            fails(FailureCode.INVALID_REQUEST) { service.supportDetail(agent, id, first.nextMessageCursor) }
        }

        @Test fun `customer http intake returns only result identifiers`() {
            val body = """{"title":"문의 제목","category":"OTHER","content":"상품 이용에 대한 문의입니다."}"""
            mvc
                .perform(
                    post(
                        "/api/v1/me/support-inquiries",
                    ).with(
                        customerJwt(customer),
                    ).with(csrf().asHeader())
                        .header("Idempotency-Key", "http-inquiry-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body),
                ).andExpect(
                    status().isCreated,
                ).andExpect(
                    header().string("Cache-Control", "no-store"),
                ).andExpect(jsonPath("$.inquiryId").isString)
                .andExpect(jsonPath("$.caseId").doesNotExist())
        }

        @Test fun `reassignment invalidates former agent reply and missing order fails intake`() {
            fails(FailureCode.RESOURCE_NOT_FOUND) {
                service.create(
                    customer,
                    "missing-order-key",
                    "주문 문의",
                    CustomerInquiryCategory.ORDER_STATUS,
                    "주문을 확인해 주세요.",
                    "BF-7K3M-9Q2P",
                    "inquiry-test",
                )
            }
            assertThat(count("support_customer_inquiry")).isZero()
            val id = create().inquiryId
            val case = service.claim(agent, id, "claim-inquiry-key", 0, "inquiry-test")
            val first = service.supportDetail(agent, id, null)
            caseService.assign(
                AssignSupportCaseCommand(
                    agent,
                    case.caseId,
                    "assign-second-key",
                    secondAgent,
                    first.caseVersion!!,
                    "담당 상담원 변경",
                    "inquiry-test",
                ),
            )
            assertThat(service.supportDetail(agent, id, null).canReply).isFalse()
            assertThat(service.supportDetail(secondAgent, id, null).canReply).isTrue()
            fails(
                FailureCode.ACCESS_DENIED,
            ) { service.supportMessage(agent, id, "former-agent-key", 1, first.caseVersion, "확인 중입니다.", "inquiry-test") }
        }

        @Test fun `terminal transition serializes with a customer message`() {
            val id = create().inquiryId
            val case = service.claim(agent, id, "claim-inquiry-key", 0, "inquiry-test")
            val first = service.supportDetail(agent, id, null)
            caseService.transition(
                TransitionSupportCaseCommand(
                    agent,
                    case.caseId,
                    "start-case-key",
                    SupportCaseState.IN_PROGRESS,
                    first.caseVersion!!,
                    "상담 시작",
                    "inquiry-test",
                ),
            )
            val active = service.supportDetail(agent, id, null)
            val results =
                concurrent(
                    listOf(
                        {
                            runCatching {
                                service.customerMessage(customer, id, "racing-message-key", 1, "추가 확인 요청", "inquiry-test")
                                true
                            }
                        },
                        {
                            runCatching {
                                caseService.transition(
                                    TransitionSupportCaseCommand(
                                        agent,
                                        case.caseId,
                                        "finish-case-key",
                                        SupportCaseState.RESOLVED,
                                        active.caseVersion!!,
                                        "처리 확인 완료",
                                        "inquiry-test",
                                    ),
                                )
                                false
                            }
                        },
                    ),
                )
            assertThat(results[1].isSuccess).isTrue()
            val final = service.customerDetail(customer, id, null)
            assertThat(final.inquiry.state).isEqualTo(CustomerInquiryState.RESOLVED)
            assertThat(final.messages.size).isEqualTo(if (results[0].isSuccess) 2 else 1)
            if (results[0].isFailure) {
                assertThat(
                    (results[0].exceptionOrNull() as DomainFailure).code,
                ).isEqualTo(FailureCode.RESOURCE_STATE_CONFLICT)
            }
        }

        @Test fun `list cursor binds customer and queue filter and expired replay cleanup preserves content`() {
            repeat(
                21,
            ) { n -> service.create(customer, "list-inquiry-$n", "문의 $n", CustomerInquiryCategory.OTHER, "상품 확인 요청", null, "inquiry-test") }
            val first = service.customerList(customer, null)
            assertThat(first.items).hasSize(20)
            assertThat(service.customerList(customer, first.nextCursor).items).hasSize(1)
            fails(FailureCode.INVALID_REQUEST) { service.customerList(other, first.nextCursor) }
            val staff = service.supportList(agent, true, null)
            fails(FailureCode.INVALID_REQUEST) { service.supportList(agent, false, staff.nextCursor) }
            assertThat(cleanup.deleteExpiredInquiryCommands(Instant.now().plusSeconds(91L * 86400), 5)).isEqualTo(5)
            assertThat(count("support_customer_inquiry_command")).isEqualTo(16)
            assertThat(count("support_customer_inquiry_message")).isEqualTo(21)
            val id = first.items.first().inquiryId
            assertThatThrownBy {
                jdbc.update("UPDATE support_customer_inquiry SET customer_id = ? WHERE id = ?", other, id)
            }.isInstanceOf(org.springframework.dao.DataIntegrityViolationException::class.java)
            assertThatThrownBy {
                jdbc.update("UPDATE support_customer_inquiry_message SET content = 'rewritten' WHERE inquiry_id = ?", id)
            }.isInstanceOf(org.springframework.dao.DataIntegrityViolationException::class.java)
        }

        private fun create() =
            service.create(
                customer,
                "create-inquiry-key",
                "결제 문의",
                CustomerInquiryCategory.PAYMENT_OR_REFUND,
                "결제 내역 확인 요청",
                null,
                "inquiry-test",
            )

        private fun count(table: String): Long = jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java)!!

        private fun fails(
            code: FailureCode,
            block: () -> Any?,
        ) = assertThatThrownBy {
            block()
        }.isInstanceOfSatisfying(DomainFailure::class.java) { assertThat(it.code).isEqualTo(code) }

        private fun customerJwt(id: UUID) =
            jwt()
                .jwt {
                    it.subject(id.toString()).claim("roles", listOf("CUSTOMER"))
                }.authorities(SimpleGrantedAuthority("ROLE_CUSTOMER"))

        private fun rejectAudit() {
            jdbc.execute(
                "CREATE FUNCTION reject_inquiry_test_audit() RETURNS trigger LANGUAGE plpgsql " +
                    "AS 'BEGIN RAISE EXCEPTION ''test audit failure''; END'",
            )
            jdbc.execute(
                "CREATE TRIGGER inquiry_test_audit_failure BEFORE INSERT ON operations_audit_record " +
                    "FOR EACH ROW EXECUTE FUNCTION reject_inquiry_test_audit()",
            )
        }

        private fun <T> concurrent(tasks: List<() -> T>): List<T> {
            val executor = Executors.newFixedThreadPool(tasks.size)
            val start = CountDownLatch(1)
            try {
                val futures =
                    tasks.map { task ->
                        executor.submit(
                            Callable {
                                check(start.await(10, TimeUnit.SECONDS))
                                task()
                            },
                        )
                    }
                start.countDown()
                return futures.map { it.get(30, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
            }
        }
    }
