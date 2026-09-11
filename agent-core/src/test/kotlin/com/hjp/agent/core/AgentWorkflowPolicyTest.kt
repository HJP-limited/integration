package com.hjp.agent.core

import com.hjp.agent.contract.ModelToolCall
import com.hjp.tool.contract.ConfirmationPolicy
import com.hjp.tool.contract.ContractVersion
import com.hjp.tool.contract.PiiLevel
import com.hjp.tool.contract.ToolCapabilityId
import com.hjp.tool.contract.ToolContract
import com.hjp.tool.contract.ToolEffect
import com.hjp.tool.contract.ToolExecutionResult
import com.hjp.tool.contract.ToolPresentation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentWorkflowPolicyTest {
    @Test
    fun `unresolved multi-candidate contact blocks downstream datetime`() {
        val workflow = turn("내일 오전 10시에 미팅 잡아줘")
        workflow.seedUnresolvedContactObligation(2)

        assertRejected(
            workflow.validate(call("get_current_datetime"), DATETIME),
            WorkflowRejectReason.CONTACT_SELECTION_REQUIRED,
        )
        assertTrue(workflow.pendingTerminalTool() == null)
    }

    @Test
    fun `standalone calendar datetime remains allowed without contact obligation`() {
        val workflow = turn("내일 오전 10시에 운동 일정 만들어줘")
        assertAllowed(workflow.validate(call("get_current_datetime"), DATETIME))
    }

    @Test
    fun `direct email compose with complete arguments is allowed`() {
        val workflow = turn("test@example.com에게 감사 메일 작성해줘.")

        assertAllowed(workflow.validate(
            call("open_compose", "channel" to "email", "to" to "test@example.com",
                "subject" to "감사드립니다", "body" to "도와주셔서 감사합니다."),
            COMPOSE,
        ))
    }

    @Test
    fun `email requires nonblank subject and body`() {
        val workflow = turn("test@example.com에게 감사 메일 작성해줘.")

        assertRejected(
            workflow.validate(
                call("open_compose", "channel" to "email", "to" to "test@example.com",
                    "subject" to "", "body" to ""),
                COMPOSE,
            ),
            WorkflowRejectReason.INVALID_ARGUMENTS,
        )
    }

    @Test
    fun `sms rejects subject`() {
        val workflow = turn("010-1234-5678에게 감사 문자 작성해줘.")

        assertRejected(
            workflow.validate(
                call("open_compose", "channel" to "sms", "to" to "010-1234-5678",
                    "subject" to "제목", "body" to "감사합니다."),
                COMPOSE,
            ),
            WorkflowRejectReason.INVALID_ARGUMENTS,
        )
    }

    @Test
    fun `name based compose requires search then detail and verified value`() {
        val workflow = turn("김지원에게 감사 메일 작성해줘.")
        val compose = call(
            "open_compose", "channel" to "email", "to" to "jiwon@example.com",
            "subject" to "감사", "body" to "감사드립니다.",
        )
        assertRejected(
            workflow.validate(compose, COMPOSE),
            WorkflowRejectReason.CONTACT_LOOKUP_REQUIRED,
        )

        val search = call("search_contacts", "query" to "김지원")
        assertAllowed(workflow.validate(search, SEARCH))
        workflow.recordResult(search, success(search, buildJsonObject {
            put("results", buildJsonArray {
                add(buildJsonObject { put("card_id", "card-1") })
            })
            put("count", 1)
        }))
        assertRejected(
            workflow.validate(compose, COMPOSE),
            WorkflowRejectReason.CONTACT_DETAIL_REQUIRED,
        )

        val get = call("get_contact", "card_id" to "card-1", "purpose" to "email")
        assertAllowed(workflow.validate(get, GET))
        workflow.recordResult(get, success(get, buildJsonObject {
            put("card_id", "card-1")
            put("email", "jiwon@example.com")
            put("mobile", "010-1234-5678")
        }))
        assertAllowed(workflow.validate(compose, COMPOSE))

        assertRejected(
            workflow.validate(
                call("open_compose", "channel" to "email", "to" to "made-up@example.com",
                    "subject" to "감사", "body" to "감사드립니다."),
                COMPOSE,
            ),
            WorkflowRejectReason.CONTACT_VALUE_NOT_VERIFIED,
        )
    }

    @Test
    fun `compact person search is allowed without a card noun`() {
        val workflow = turn("우성씨 찾아줘.")
        val search = call("search_contacts", "query" to "우성")

        assertAllowed(workflow.validate(search, SEARCH))
    }

    @Test
    fun `tool grounded prior turn card id can continue without repeating search`() {
        val workflow = turn("그분에게 감사 메일 작성 화면 열어줘.")
        workflow.seedTrustedContactProvenance("room-42")
        val get = call("get_contact", "card_id" to "room-42", "purpose" to "email")
        assertAllowed(workflow.validate(get, GET))
        workflow.recordResult(get, success(get, buildJsonObject {
            put("card_id", "room-42")
            put("email", "grounded@example.com")
        }))
        assertAllowed(workflow.validate(
            call(
                "open_compose",
                "channel" to "email",
                "to" to "grounded@example.com",
                "subject" to "감사",
                "body" to "감사드립니다.",
            ),
            COMPOSE,
        ))
    }

    @Test
    fun `contact compose with card id is repaired through purpose bound fresh read`() {
        val workflow = turn("그분에게 감사 메일 작성해줘.")
        workflow.seedTrustedContactProvenance("T001")
        val invalidRecipient = call(
            "open_compose", "channel" to "email", "to" to "T001",
            "subject" to "감사", "body" to "감사드립니다.",
        )

        val first = workflow.validate(invalidRecipient, COMPOSE) as WorkflowValidationResult.Reject
        assertEquals(WorkflowRejectReason.CONTACT_DETAIL_REQUIRED, first.rejection.reason)
        assertEquals(setOf("get_contact"), first.rejection.allowedNextTools)
        assertEquals("T001", (first.rejection.repairArguments?.get("card_id") as JsonPrimitive).content)
        assertEquals("email", (first.rejection.repairArguments?.get("purpose") as JsonPrimitive).content)
        workflow.rejectionResponse(invalidRecipient, first.rejection)
        assertEquals("get_contact", workflow.pendingRejectionRepairTool())
        assertTrue(
            workflow.continuationPrompt("get_contact", invalidRecipient.callId, "open_compose")
                .text.contains("\"card_id\":\"T001\""),
        )

        assertRejected(
            workflow.validate(call("get_contact", "card_id" to "T001", "purpose" to "display"), GET),
            WorkflowRejectReason.CONTACT_DETAIL_REQUIRED,
        )
        val fresh = call("get_contact", "card_id" to "T001", "purpose" to "email")
        assertAllowed(workflow.validate(fresh, GET))
        workflow.recordResult(fresh, success(fresh, buildJsonObject {
            put("card_id", "T001")
            put("email", "daeun@example.com")
        }))
        assertEquals("open_compose", workflow.pendingTerminalTool())
        assertTrue(
            workflow.continuationPrompt("open_compose", fresh.callId, "get_contact")
                .text.contains("\"to\":\"daeun@example.com\""),
        )
        val repairedCompose = workflow.normalizeArguments(call(
            "open_compose", "channel" to "email", "to" to "T001",
            "subject" to "감사", "body" to "감사드립니다.",
        ), COMPOSE)
        assertEquals("daeun@example.com", (repairedCompose.arguments["to"] as JsonPrimitive).content)
        assertAllowed(workflow.validate(repairedCompose, COMPOSE))
        workflow.recordResult(repairedCompose, success(repairedCompose, buildJsonObject {
            put("opened", true)
        }))
        assertEquals(null, workflow.pendingTerminalTool())
    }

    @Test
    fun `verified email owns drifting compose recipient while preserving draft`() {
        val workflow = turn("그분에게 감사 메일 작성해줘.")
        workflow.seedTrustedContactProvenance("T001")
        val fresh = call("get_contact", "card_id" to "T001", "purpose" to "email")
        assertAllowed(workflow.validate(fresh, GET))
        workflow.recordResult(fresh, success(fresh, buildJsonObject {
            put("card_id", "T001")
            put("email", "verified@example.com")
        }))

        val original = call(
            "open_compose", "channel" to "email", "to" to "stale@example.com",
            "subject" to "업무", "body" to "확인 부탁드립니다.",
        )
        val normalized = workflow.normalizeArguments(original, COMPOSE)
        assertEquals("verified@example.com", (normalized.arguments["to"] as JsonPrimitive).content)
        assertEquals("업무", (normalized.arguments["subject"] as JsonPrimitive).content)
        assertEquals("확인 부탁드립니다.", (normalized.arguments["body"] as JsonPrimitive).content)
        assertAllowed(workflow.validate(normalized, COMPOSE))
    }


    @Test
    fun `calendar arguments use fresh verified email and typed start time`() {
        val workflow = turn("그 사람과 2027년 9월 15일 오후 3시에 회의 일정을 만들어줘.")
        workflow.seedTrustedContactProvenance("T001")
        val original = ModelToolCall("calendar", "create_calendar_event", buildJsonObject {
            put("title", "회의")
            put("start_time", "garbage")
            put("attendee_emails", JsonArray(listOf(JsonPrimitive("wrong@example.com"))))
        })
        val initialRejection = workflow.validate(original, CALENDAR) as WorkflowValidationResult.Reject
        assertEquals(WorkflowRejectReason.CONTACT_DETAIL_REQUIRED, initialRejection.rejection.reason)
        workflow.rejectionResponse(original, initialRejection.rejection)

        val fresh = call("get_contact", "card_id" to "T001", "purpose" to "calendar")
        assertAllowed(workflow.validate(fresh, GET))
        workflow.recordResult(fresh, success(fresh, buildJsonObject {
            put("card_id", "T001")
            put("name", "손다은")
            put("email", "daeun@example.com")
        }))

        val normalized = workflow.normalizeArguments(original, CALENDAR)
        assertEquals(
            "daeun@example.com",
            ((normalized.arguments["attendee_emails"] as JsonArray).single() as JsonPrimitive).content,
        )
        assertEquals("2027-09-15T15:00", (normalized.arguments["start_time"] as JsonPrimitive).content)
        assertAllowed(workflow.validate(normalized, CALENDAR))

        val drifted = workflow.normalizeArguments(ModelToolCall(
            "calendar-drift", "create_calendar_event", buildJsonObject {
                put("title", "회의")
                put("start_time", "2027-09-15T15:00")
                put("attendee_emails", JsonArray(listOf(JsonPrimitive("other@example.com"))))
            },
        ), CALENDAR)
        assertAllowed(workflow.validate(drifted, CALENDAR))
    }

    @Test
    fun `calendar and update each require a purpose appropriate fresh read`() {
        val calendar = turn("그 사람과 2027년 9월 15일 오후 3시에 회의 일정 만들어줘.")
        calendar.seedTrustedContactProvenance("T001")
        val calendarCall = call(
            "create_calendar_event", "title" to "회의", "start_time" to "2027-09-15T15:00",
        )
        val calendarRejection = calendar.validate(calendarCall, CALENDAR)
            as WorkflowValidationResult.Reject
        assertEquals(WorkflowRejectReason.CONTACT_DETAIL_REQUIRED, calendarRejection.rejection.reason)
        assertEquals(
            "calendar",
            (calendarRejection.rejection.repairArguments?.get("purpose") as JsonPrimitive).content,
        )

        val update = turn("그 사람 명함의 메모를 중요 고객으로 수정해줘.")
        update.seedTrustedContactProvenance("T001")
        val updateRejection = update.validate(
            ModelToolCall("update", "update_business_card", buildJsonObject {
                put("card_id", "T001")
                putJsonObject("updates") { put("memo", "중요 고객") }
            }),
            UPDATE,
        ) as WorkflowValidationResult.Reject
        assertEquals(WorkflowRejectReason.CONTACT_DETAIL_REQUIRED, updateRejection.rejection.reason)
        assertEquals(
            "display",
            (updateRejection.rejection.repairArguments?.get("purpose") as JsonPrimitive).content,
        )
    }

    @Test
    fun `update canonicalization drops a clear field that is simultaneously updated`() {
        val workflow = turn("그 사람 명함의 메모를 중요 고객으로 수정해줘.")
        workflow.seedTrustedContactProvenance("T001")
        val fresh = call("get_contact", "card_id" to "T001", "purpose" to "display")
        assertAllowed(workflow.validate(fresh, GET))
        workflow.recordResult(fresh, success(fresh, buildJsonObject { put("card_id", "T001") }))
        val contradictory = ModelToolCall("update", "update_business_card", buildJsonObject {
            put("card_id", "T001")
            put("clear_fields", JsonArray(listOf(JsonPrimitive("memo"))))
            putJsonObject("updates") { put("memo", "중요 고객") }
        })

        val normalized = workflow.normalizeArguments(contradictory, UPDATE)
        assertEquals(null, normalized.arguments["clear_fields"])
        assertAllowed(workflow.validate(normalized, UPDATE))
    }

    @Test
    fun `empty and ambiguous search results block detail`() {
        val empty = turn("없는사람에게 감사 메일 작성해줘.")
        val searchEmpty = call("search_contacts", "query" to "없는사람")
        empty.recordResult(searchEmpty, success(searchEmpty, buildJsonObject {
            put("results", JsonArray(emptyList()))
            put("count", 0)
        }))
        assertRejected(
            empty.validate(call("get_contact", "card_id" to "invented"), GET),
            WorkflowRejectReason.CONTACT_NOT_FOUND,
        )

        val ambiguous = turn("김지원에게 감사 문자 작성해줘.")
        val searchMany = call("search_contacts", "query" to "김지원")
        ambiguous.recordResult(searchMany, success(searchMany, buildJsonObject {
            put("results", buildJsonArray {
                add(buildJsonObject { put("card_id", "card-1") })
                add(buildJsonObject { put("card_id", "card-2") })
            })
            put("count", 2)
        }))
        assertRejected(
            ambiguous.validate(call("get_contact", "card_id" to "card-1"), GET),
            WorkflowRejectReason.CONTACT_SELECTION_REQUIRED,
        )
    }

    @Test
    fun `relative calendar requires current time and validates calculated date`() {
        val workflow = turn("다음 주 화요일 오후 2시에 팀 회의 일정 만들어줘.")
        val calendar = call(
            "create_calendar_event",
            "title" to "팀 회의",
            "start_time" to "2026-07-28T14:00",
        )
        assertRejected(
            workflow.validate(calendar, CALENDAR),
            WorkflowRejectReason.CURRENT_DATETIME_REQUIRED,
        )

        val now = call("get_current_datetime")
        assertAllowed(workflow.validate(now, DATETIME))
        workflow.recordResult(now, success(now, buildJsonObject {
            put("date", "2026-07-24")
            put("datetime", "2026-07-24T09:00:00+09:00")
        }))
        assertAllowed(workflow.validate(calendar, CALENDAR))
        assertRejected(
            workflow.validate(
                call("create_calendar_event", "title" to "팀 회의",
                    "start_time" to "2026-07-29T14:00"),
                CALENDAR,
            ),
            WorkflowRejectReason.INVALID_DATETIME,
        )
    }

    @Test
    fun `absolute calendar rejects unnecessary time lookup and non-canonical start_time`() {
        val workflow = turn("2026년 7월 30일 오후 2시에 제품 데모 일정 만들어줘.")

        assertRejected(
            workflow.validate(call("get_current_datetime"), DATETIME),
            WorkflowRejectReason.UNNECESSARY_TOOL_CALL,
        )
        assertAllowed(workflow.validate(
            call("create_calendar_event", "title" to "제품 데모",
                "start_time" to "2026-07-30T14:00"),
            CALENDAR,
        ))
        // Contract change, deliberate: a zero seconds component is a notation difference, not a
        // different instant, and the real Gemma artifact writes it every time. The kernel
        // canonicalises it before validation, so the validator sees minute precision here too.
        val withZeroSeconds = call(
            "create_calendar_event", "title" to "제품 데모", "start_time" to "2026-07-30T14:00:00",
        )
        val canonical = workflow.normalizeArguments(withZeroSeconds, CALENDAR)
        assertEquals(
            "2026-07-30T14:00",
            (canonical.arguments["start_time"] as JsonPrimitive).content,
        )
        assertAllowed(workflow.validate(canonical, CALENDAR))

        // Everything that would change the meaning is still refused, and refused specifically.
        listOf(
            "2026-07-30T14:00:30" to "초",
            "2026-07-30T14:00:00Z" to "시간대",
            "2026-07-30T14:00+09:00" to "시간대",
            "2026-07-30 14:00" to "형식",
            "2026-02-30T14:00" to "존재하지 않는",
        ).forEach { (value, expectedReason) ->
            val bad = call("create_calendar_event", "title" to "제품 데모", "start_time" to value)
            val normalized = workflow.normalizeArguments(bad, CALENDAR)
            assertEquals("$value must not be rewritten", value,
                (normalized.arguments["start_time"] as JsonPrimitive).content)
            val result = workflow.validate(normalized, CALENDAR)
            assertTrue("$value should be rejected, got $result",
                result is WorkflowValidationResult.Reject)
            val rejection = (result as WorkflowValidationResult.Reject).rejection
            assertEquals(value, WorkflowRejectReason.INVALID_DATETIME, rejection.reason)
            assertTrue(
                "$value -> ${rejection.messageKo}",
                rejection.messageKo.contains(expectedReason),
            )
        }
    }

    @Test
    fun `end_time goes through the same contract as start_time`() {
        val workflow = turn("2026년 7월 30일 오후 2시에 제품 데모 일정 만들어줘.")

        // Canonicalised, like start_time.
        val zeroSeconds = call(
            "create_calendar_event", "title" to "제품 데모",
            "start_time" to "2026-07-30T14:00", "end_time" to "2026-07-30T15:00:00",
        )
        val canonical = workflow.normalizeArguments(zeroSeconds, CALENDAR)
        assertEquals("2026-07-30T15:00", (canonical.arguments["end_time"] as JsonPrimitive).content)
        assertAllowed(workflow.validate(canonical, CALENDAR))

        // A value the canonicalizer refuses is a validation error, not a value that survives.
        listOf(
            "2026-07-30T15:00:30" to "초",
            "2026-07-30T15:00Z" to "시간대",
            "2026-07-30T15:00+09:00" to "시간대",
            "2026-02-30T15:00" to "존재하지 않는",
            "2026-07-30 15:00" to "형식",
        ).forEach { (value, reason) ->
            val bad = call(
                "create_calendar_event", "title" to "제품 데모",
                "start_time" to "2026-07-30T14:00", "end_time" to value,
            )
            val normalized = workflow.normalizeArguments(bad, CALENDAR)
            assertEquals("$value must not be rewritten", value,
                (normalized.arguments["end_time"] as JsonPrimitive).content)
            val result = workflow.validate(normalized, CALENDAR)
            assertTrue("$value should be rejected, got $result", result is WorkflowValidationResult.Reject)
            val rejection = (result as WorkflowValidationResult.Reject).rejection
            assertEquals(value, WorkflowRejectReason.INVALID_DATETIME, rejection.reason)
            assertTrue("$value -> ${rejection.messageKo}", rejection.messageKo.contains(reason))
        }
    }

    @Test
    fun `an end_time at or before the start_time is refused before the plugin runs`() {
        val workflow = turn("2026년 7월 30일 오후 2시에 제품 데모 일정 만들어줘.")

        listOf("2026-07-30T14:00", "2026-07-30T13:00", "2026-07-29T14:00").forEach { end ->
            val result = workflow.validate(
                workflow.normalizeArguments(
                    call("create_calendar_event", "title" to "제품 데모",
                        "start_time" to "2026-07-30T14:00", "end_time" to end),
                    CALENDAR,
                ),
                CALENDAR,
            )
            assertRejected(result, WorkflowRejectReason.INVALID_DATETIME)
        }
    }

    @Test
    fun `update by name requires verified contact and rejects overlapping clear`() {
        val workflow = turn("김지원 명함 직함을 이사로 바꿔줘.")
        val update = updateCall(clearTitle = false)
        assertRejected(
            workflow.validate(update, UPDATE),
            WorkflowRejectReason.CONTACT_LOOKUP_REQUIRED,
        )
        val search = call("search_contacts", "query" to "김지원")
        workflow.recordResult(search, success(search, buildJsonObject {
            put("results", buildJsonArray {
                add(buildJsonObject { put("card_id", "card-1") })
            })
        }))
        val get = call("get_contact", "card_id" to "card-1")
        workflow.recordResult(get, success(get, buildJsonObject {
            put("card_id", "card-1")
        }))
        assertAllowed(workflow.validate(update, UPDATE))
        assertRejected(
            workflow.validate(updateCall(clearTitle = true), UPDATE),
            WorkflowRejectReason.INVALID_ARGUMENTS,
        )
    }

    @Test
    fun `preview unsupported delete invalid recipient and missing time never execute`() {
        assertRejected(
            turn("감사 이메일 예시를 보여줘.").validate(
                call("open_compose", "channel" to "email", "to" to "test@example.com",
                    "subject" to "감사", "body" to "감사합니다."),
                COMPOSE,
            ),
            WorkflowRejectReason.TOOL_NOT_REQUESTED,
        )
        assertRejected(
            turn("김지원 명함을 완전히 삭제해줘.").validate(
                call("search_contacts", "query" to "김지원"),
                SEARCH,
            ),
            WorkflowRejectReason.UNSUPPORTED_REQUEST,
        )
        assertRejected(
            turn("김지원에게 전화 걸어줘.").validate(
                call("search_contacts", "query" to "김지원"),
                SEARCH,
            ),
            WorkflowRejectReason.UNSUPPORTED_REQUEST,
        )
        assertRejected(
            turn("test-at-example에게 감사 메일 작성해줘.").validate(
                call("open_compose", "channel" to "email", "to" to "test-at-example@example.com",
                    "subject" to "감사", "body" to "감사합니다."),
                COMPOSE,
            ),
            WorkflowRejectReason.INVALID_EMAIL,
        )
        assertRejected(
            turn("내일 고객 미팅 일정 만들어줘.").validate(
                call("get_current_datetime"),
                DATETIME,
            ),
            WorkflowRejectReason.MISSING_REQUIRED_INFORMATION,
        )
    }

    @Test
    fun `final guard blocks incomplete workflow rejected reason and false send claim`() {
        val incomplete = turn("test@example.com에게 감사 메일 작성해줘.")
            .validateFinal("감사 메일 초안을 만들었습니다.")
        assertTrue(incomplete is WorkflowFinalValidationResult.Replace)

        val rejected = turn("김지원에게 전화 걸어줘.")
        val rejectedCall = call("search_contacts", "query" to "김지원")
        val rejection = rejected.validate(rejectedCall, SEARCH) as WorkflowValidationResult.Reject
        rejected.rejectionResponse(rejectedCall, rejection.rejection)
        val rejectedFinal = rejected.validateFinal("전화 연결을 준비하겠습니다.")
            as WorkflowFinalValidationResult.Replace
        assertEquals(WorkflowRejectReason.UNSUPPORTED_REQUEST, rejectedFinal.reason)

        val completed = turn("test@example.com에게 감사 메일 작성해줘.")
        val compose = call(
            "open_compose",
            "channel" to "email",
            "to" to "test@example.com",
            "subject" to "감사",
            "body" to "감사합니다.",
        )
        assertAllowed(completed.validate(compose, COMPOSE))
        completed.recordResult(
            compose,
            ToolExecutionResult.Success(
                compose.callId,
                COMPOSE.capabilityId,
                COMPOSE.version,
                1,
                buildJsonObject { put("opened", true) },
            ),
        )
        val falseCompletion = completed.validateFinal("이메일을 전송했습니다.")
            as WorkflowFinalValidationResult.Replace
        assertEquals(
            "작성 화면을 열었습니다. 실제 전송 여부는 작성 화면에서 확인해 주세요.",
            falseCompletion.safeMessageKo,
        )
    }

    @Test
    fun `calendar surface requiring confirmation never becomes a saved-event claim`() {
        val workflow = turn("내일 오후 3시에 일정 만들어줘.")
        val calendar = call("create_calendar_event")
        workflow.recordResult(
            calendar,
            success(
                calendar,
                buildJsonObject {
                    put("opened", true)
                    put("requires_user_confirmation", true)
                },
            ),
        )

        val result = workflow.validateFinal("일정이 생성되었습니다.")
            as WorkflowFinalValidationResult.Replace
        assertEquals(
            "캘린더 일정 작성 화면을 열었습니다. 내용을 확인한 뒤 저장해 주세요.",
            result.safeMessageKo,
        )
    }

    @Test
    fun `typed terminal surfaces expose only truthful generation fallbacks`() {
        val composeWorkflow = turn("test@example.com에게 메일 초안 열어줘")
        val compose = call(
            "open_compose", "channel" to "email", "to" to "test@example.com",
            "subject" to "안내", "body" to "안녕하세요.",
        )
        composeWorkflow.recordResult(compose, success(compose, buildJsonObject {
            put("opened", true); put("requires_user_confirmation", true)
        }))
        assertEquals(
            "메일 작성 화면을 열었습니다. 내용을 확인한 뒤 전송해 주세요.",
            composeWorkflow.terminalSurfaceFallback(),
        )

        val calendarWorkflow = turn("내일 오후 3시에 일정 만들어줘")
        val calendar = call("create_calendar_event")
        calendarWorkflow.recordResult(calendar, success(calendar, buildJsonObject {
            put("opened", true); put("requires_user_confirmation", true)
        }))
        assertEquals(
            "캘린더 일정 작성 화면을 열었습니다. 내용을 확인한 뒤 저장해 주세요.",
            calendarWorkflow.terminalSurfaceFallback(),
        )
    }

    @Test
    fun `typed tool failure always replaces model prose regardless of its wording`() {
        val workflow = turn("test@example.com에게 감사 메일 작성해줘.")
        val compose = call(
            "open_compose", "channel" to "email", "to" to "test@example.com",
            "subject" to "감사", "body" to "감사드립니다.",
        )
        workflow.recordResult(
            compose,
            ToolExecutionResult.Failure(
                compose.callId,
                COMPOSE.capabilityId,
                COMPOSE.version,
                1,
                com.hjp.tool.contract.ToolError(
                    com.hjp.tool.contract.StandardToolErrorCodes.TOOL_EXECUTION_FAILED,
                    "작성 화면을 열지 못했습니다.",
                    false,
                ),
            ),
        )

        val result = workflow.validateFinal("잠시 기다려 주세요.") as WorkflowFinalValidationResult.Replace
        assertEquals("작성 화면을 열지 못했습니다.", result.safeMessageKo)
    }

    @Test
    fun `phone number in user text is not accepted as a contact card id`() {
        val workflow = turn("010-1234-5678에게 감사 문자 작성해줘.")

        assertRejected(
            workflow.validate(
                call("get_contact", "card_id" to "010-1234-5678", "purpose" to "sms"),
                GET,
            ),
            WorkflowRejectReason.CONTACT_LOOKUP_REQUIRED,
        )
    }

    @Test
    fun `email with whitespace inside an address-like token is rejected`() {
        val workflow = turn("a b@example.com에게 안내 메일 작성해줘.")
        assertRejected(
            workflow.validate(
                call(
                    "open_compose", "channel" to "email", "to" to "b@example.com",
                    "subject" to "안내", "body" to "안내드립니다.",
                ),
                COMPOSE,
            ),
            WorkflowRejectReason.INVALID_EMAIL,
        )
    }

    @Test
    fun `rejection response is structured for one model correction`() {
        val workflow = turn("김지원에게 감사 메일 작성해줘.")
        val call = call(
            "open_compose", "channel" to "email", "to" to "jiwon@example.com",
            "subject" to "감사", "body" to "감사합니다.",
        )
        val rejected = workflow.validate(call, COMPOSE) as WorkflowValidationResult.Reject

        val payload = workflow.rejectionResponse(call, rejected.rejection).payload

        assertEquals("rejected", (payload["status"] as JsonPrimitive).content)
        assertEquals(
            "CONTACT_LOOKUP_REQUIRED",
            (payload["reason"] as JsonPrimitive).content,
        )
        assertEquals(
            "search_contacts",
            ((payload["allowed_next_tools"] as JsonArray).single() as JsonPrimitive).content,
        )
    }

    private fun turn(text: String) =
        ProductionAgentWorkflowPolicy().startTurn(text, "Asia/Seoul")

    private fun assertAllowed(result: WorkflowValidationResult) {
        assertTrue("expected allow, got $result", result is WorkflowValidationResult.Allow)
    }

    private fun assertRejected(
        result: WorkflowValidationResult,
        reason: WorkflowRejectReason,
    ) {
        assertTrue("expected rejection, got $result", result is WorkflowValidationResult.Reject)
        assertEquals(reason, (result as WorkflowValidationResult.Reject).rejection.reason)
    }

    private fun call(name: String, vararg values: Pair<String, String>) =
        ModelToolCall("call-${name}-${values.hashCode()}", name, buildJsonObject {
            values.forEach { (key, value) -> put(key, value) }
        })

    private fun updateCall(clearTitle: Boolean) =
        ModelToolCall("call-update-$clearTitle", "update_business_card", buildJsonObject {
            put("card_id", "card-1")
            putJsonObject("updates") { put("title", "이사") }
            if (clearTitle) put("clear_fields", buildJsonArray { add(JsonPrimitive("title")) })
        })

    private fun success(call: ModelToolCall, data: kotlinx.serialization.json.JsonObject) =
        ToolExecutionResult.Success(
            call.callId,
            ToolCapabilityId("test.result"),
            ContractVersion(1, 0),
            1,
            data,
        )

    companion object {
        private fun string(enum: List<String>? = null) = buildJsonObject {
            put("type", "string")
            enum?.let { put("enum", JsonArray(it.map(::JsonPrimitive))) }
        }

        private fun contract(
            name: String,
            required: List<String>,
            properties: kotlinx.serialization.json.JsonObject,
        ) = ToolContract(
            ToolCapabilityId("test.$name"),
            name,
            ContractVersion(1, 0),
            name,
            buildJsonObject {
                put("type", "object")
                put("additionalProperties", false)
                put("required", JsonArray(required.map(::JsonPrimitive)))
                put("properties", properties)
            },
            buildJsonObject { put("type", "object") },
            ToolEffect.READ_ONLY,
            ConfirmationPolicy.NONE,
            PiiLevel.NONE,
            PiiLevel.NONE,
            defaultTimeoutMillis = 1_000,
            presentation = ToolPresentation("실행", "완료", "불가"),
        )

        private val SEARCH = contract("search_contacts", listOf("query"), buildJsonObject {
            put("query", string())
        })
        private val GET = contract("get_contact", listOf("card_id"), buildJsonObject {
            put("card_id", string())
            put("purpose", string(listOf("display", "email", "sms", "calendar")))
        })
        private val COMPOSE = contract(
            "open_compose",
            listOf("channel", "to", "body"),
            buildJsonObject {
                put("channel", string(listOf("email", "sms")))
                put("to", string())
                put("subject", string())
                put("body", string())
            },
        )
        private val CALENDAR = contract(
            "create_calendar_event",
            listOf("title", "start_time"),
            buildJsonObject {
                put("title", string())
                put("start_time", string())
                // Mirrors the production CALENDAR_INPUT_SCHEMA, which has always declared end_time.
                put("end_time", string())
                putJsonObject("attendee_emails") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "string")
                        put("format", "email")
                    }
                }
            },
        )
        private val UPDATE = contract("update_business_card", listOf("card_id"), buildJsonObject {
            put("card_id", string())
            putJsonObject("updates") { put("type", "object") }
            putJsonObject("clear_fields") {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
            }
        })
        private val DATETIME = contract("get_current_datetime", emptyList(), buildJsonObject {
            put("timezone", string())
        })
    }
}
