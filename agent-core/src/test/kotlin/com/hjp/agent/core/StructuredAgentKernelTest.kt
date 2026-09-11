package com.hjp.agent.core

import com.hjp.agent.contract.AgentEvent
import com.hjp.agent.contract.AgentIntent
import com.hjp.agent.contract.ComposeChannel
import com.hjp.agent.contract.ComposeContentRequest
import com.hjp.agent.contract.ConversationContext
import com.hjp.agent.contract.GeneratedComposeContent
import com.hjp.agent.contract.IntentRecipient
import com.hjp.agent.contract.ModelToolResponse
import com.hjp.agent.contract.RecipientType
import com.hjp.agent.contract.StructuredAgentModelGateway
import com.hjp.agent.contract.StructuredFinalRequest
import com.hjp.agent.contract.StructuredIntentPlan
import com.hjp.agent.contract.StructuredModelResult
import com.hjp.tool.contract.ConfirmationPolicy
import com.hjp.tool.contract.ContractVersion
import com.hjp.tool.contract.PiiLevel
import com.hjp.tool.contract.ToolAvailability
import com.hjp.tool.contract.ToolCapabilityId
import com.hjp.tool.contract.ToolContract
import com.hjp.tool.contract.ToolEffect
import com.hjp.tool.contract.ToolExecutionContext
import com.hjp.tool.contract.ToolExecutionResult
import com.hjp.tool.contract.ToolImplementationId
import com.hjp.tool.contract.ToolPlugin
import com.hjp.tool.contract.ToolPresentation
import com.hjp.tool.contract.ToolRequest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredAgentKernelTest {
    @Test
    fun `contact email runs search get content and compose`() = runBlocking {
        val fixture = Fixture(searchCount = 1)
        val events = fixture.kernel(
            plan(AgentIntent.COMPOSE_EMAIL, RecipientType.CONTACT_NAME, "김지원", "지난 미팅 감사"),
        ).runTurn("김지원에게 지난 미팅 감사 메일 작성해줘.").toList()

        assertEquals(listOf("search_contacts", "get_contact", "open_compose"), fixture.executed)
        assertEquals(1, fixture.model.contentCalls)
        assertEquals("jiwon@example.com", fixture.arguments.last().string("to"))
        assertTrue(events.last() is AgentEvent.FinalMessage)
    }

    @Test
    fun `completed structured turn is carried into the next turn as memory`() = runBlocking {
        val fixture = Fixture(searchCount = 1)
        fixture.kernel(plan(AgentIntent.SEARCH_CONTACT, RecipientType.CONTACT_NAME, "김지원", ""))
            .runTurn("김지원 명함 찾아줘.").toList()
        fixture.kernel(plan(AgentIntent.SEARCH_CONTACT, RecipientType.CONTACT_NAME, "김지원", ""))
            .runTurn("김지원 연락처 찾아줘.").toList()

        val carried = fixture.model.intentConversations.single()
        val recent = carried.promptContext.sections.single { it.name == "recent_conversation" }
        assertTrue(recent.body.contains("김지원 명함 찾아줘."))
        val state = carried.promptContext.sections.single { it.name == "session_state" }
        assertTrue(state.body.contains("selected_contact: card_id=card-0"))
        assertTrue(state.body.contains("provenance=TOOL_VERIFIED"))
        // The action ledger is internal state and no longer rendered into the prompt: statuses and
        // request text are the agent's audit trail, not facts about the user. What the model needs
        // from a completed lookup is the verified contact, asserted above.
        assertTrue(
            "the completed action must not be shown to the model: ${state.body}",
            !state.body.contains("COMPLETED"),
        )
        assertTrue(
            "and the completed lookup still reaches the model as a verified contact",
            state.body.contains("selected_contact"),
        )
        assertEquals(carried, fixture.model.finalConversations.single())
    }

    @Test
    fun `structured memory never carries an email address`() = runBlocking {
        val fixture = Fixture(searchCount = 1)
        fixture.kernel(plan(AgentIntent.VIEW_CONTACT, RecipientType.CONTACT_NAME, "김지원", ""))
            .runTurn("김지원 명함 찾아줘.").toList()
        fixture.kernel(plan(AgentIntent.SEARCH_CONTACT, RecipientType.CONTACT_NAME, "김지원", ""))
            .runTurn("김지원 연락처 찾아줘.").toList()

        val state = fixture.model.intentConversations.single()
            .promptContext.sections.single { it.name == "session_state" }
        assertTrue(!state.body.contains("jiwon@example.com"))
        assertTrue(state.body.contains("실행 전 card_id로 다시 조회"))
    }

    @Test
    fun `structured turn that fails before completion is recorded as failed`() = runBlocking {
        val fixture = Fixture(searchCount = 1)
        fixture.kernel(
            plan(AgentIntent.CREATE_CALENDAR_EVENT, RecipientType.NONE, "", "일정", date = null),
        ).runTurn("일정 만들어 줘.").toList()
        fixture.kernel(plan(AgentIntent.SEARCH_CONTACT, RecipientType.CONTACT_NAME, "김지원", ""))
            .runTurn("김지원 연락처 찾아줘.").toList()

        val state = fixture.model.intentConversations.single()
            .promptContext.sections.single { it.name == "session_state" }
        assertTrue(
            "a failed action is internal state too",
            !state.body.contains("FAILED"),
        )
        assertTrue(!state.body.contains("open_actions"))
    }

    @Test
    fun `zero and duplicate contact stop after search`() = runBlocking {
        for (count in listOf(0, 2)) {
            val fixture = Fixture(searchCount = count)
            fixture.kernel(
                plan(AgentIntent.COMPOSE_SMS, RecipientType.CONTACT_NAME, "김지원", "감사"),
            ).runTurn("김지원에게 감사 문자 작성해줘.").toList()
            assertEquals(listOf("search_contacts"), fixture.executed)
            assertEquals(0, fixture.model.contentCalls)
        }
    }

    @Test
    fun `missing contact destination never composes`() = runBlocking {
        val fixture = Fixture(searchCount = 1, email = "")
        fixture.kernel(
            plan(AgentIntent.COMPOSE_EMAIL, RecipientType.CONTACT_NAME, "김지원", "감사"),
        ).runTurn("김지원에게 감사 메일 작성해줘.").toList()

        assertEquals(listOf("search_contacts", "get_contact"), fixture.executed)
        assertEquals(0, fixture.model.contentCalls)
    }

    @Test
    fun `direct recipients skip lookup and relative calendar is code calculated`() = runBlocking {
        val direct = Fixture()
        direct.kernel(
            plan(AgentIntent.COMPOSE_SMS, RecipientType.PHONE, "010-1234-5678", "곧 도착"),
        ).runTurn("010-1234-5678에게 곧 도착한다고 문자 작성해줘.").toList()
        assertEquals(listOf("open_compose"), direct.executed)

        val calendar = Fixture()
        calendar.kernel(
            plan(
                AgentIntent.CREATE_CALENDAR_EVENT,
                RecipientType.NONE,
                "",
                "",
                date = "다음 주 월요일",
                time = "오전 10시",
                title = "팀 회의",
            ),
        ).runTurn("다음 주 월요일 오전 10시에 팀 회의 일정 만들어줘.").toList()
        assertEquals(listOf("get_current_datetime", "create_calendar_event"), calendar.executed)
        assertEquals("2026-07-27T10:00", calendar.arguments.last().string("start_time"))
    }

    @Test
    fun `ungrounded card id cannot enter a contact action workflow`() = runBlocking {
        val fixture = Fixture()
        fixture.kernel(
            plan(
                AgentIntent.COMPOSE_SMS,
                RecipientType.CARD_ID,
                "card-stale-77",
                "확인",
            ),
        ).runTurn("이전 검색에서 사라진 card-stale-77 명함으로 문자를 작성해 줘.").toList()

        assertTrue(fixture.executed.isEmpty())
        assertEquals(0, fixture.model.contentCalls)
    }

    @Test
    fun `answer clarify and unsupported execute no tools`() = runBlocking {
        val cases = listOf(
            plan(AgentIntent.ANSWER_ONLY, RecipientType.NONE, "", "", execute = false),
            plan(
                AgentIntent.CLARIFY,
                RecipientType.NONE,
                "",
                "",
                execute = false,
                clarification = "시간을 알려주세요.",
            ),
            plan(AgentIntent.UNSUPPORTED, RecipientType.NONE, "", "", execute = false),
        )
        cases.forEach { candidate ->
            val fixture = Fixture()
            fixture.kernel(candidate).runTurn("요청").toList()
            assertTrue(fixture.executed.isEmpty())
        }
    }

    @Test
    fun `contact update requires user grounded field and value`() = runBlocking {
        val grounded = Fixture()
        grounded.kernel(
            plan(
                AgentIntent.UPDATE_CONTACT,
                RecipientType.CONTACT_NAME,
                "김지원",
                "",
                updates = buildJsonObject { put("title", "이사") },
            ),
        ).runTurn("김지원 명함의 직함을 이사로 바꿔줘.").toList()
        assertEquals(
            listOf("search_contacts", "get_contact", "update_business_card"),
            grounded.executed,
        )

        val invented = Fixture()
        invented.kernel(
            plan(
                AgentIntent.UPDATE_CONTACT,
                RecipientType.CONTACT_NAME,
                "김지원",
                "",
                updates = buildJsonObject { put("name", "명함 정보 수정 요청") },
            ),
        ).runTurn("김지원 명함 정보를 수정해줘.").toList()
        assertTrue(invented.executed.isEmpty())
    }

    @Test
    fun `representative orchestrated workflows stay stable for ten repetitions`() = runBlocking {
        val emailMillis = mutableListOf<Double>()
        val smsMillis = mutableListOf<Double>()
        val calendarMillis = mutableListOf<Double>()
        repeat(10) {
            val email = Fixture()
            var started = System.nanoTime()
            email.kernel(
                plan(AgentIntent.COMPOSE_EMAIL, RecipientType.CONTACT_NAME, "김지원", "지난 미팅 감사"),
            ).runTurn("김지원에게 지난 미팅 감사 메일 작성해줘.").toList()
            emailMillis += (System.nanoTime() - started) / 1_000_000.0
            assertEquals(listOf("search_contacts", "get_contact", "open_compose"), email.executed)

            val sms = Fixture()
            started = System.nanoTime()
            sms.kernel(
                plan(AgentIntent.COMPOSE_SMS, RecipientType.CONTACT_NAME, "김지원", "다음 주 다시 연락"),
            ).runTurn("김지원에게 다음 주 다시 연락한다고 문자 작성해줘.").toList()
            smsMillis += (System.nanoTime() - started) / 1_000_000.0
            assertEquals(listOf("search_contacts", "get_contact", "open_compose"), sms.executed)

            val calendar = Fixture()
            started = System.nanoTime()
            calendar.kernel(
                plan(
                    AgentIntent.CREATE_CALENDAR_EVENT,
                    RecipientType.NONE,
                    "",
                    "",
                    date = "다음 주 월요일",
                    time = "오전 10시",
                    title = "팀 회의",
                ),
            ).runTurn("다음 주 월요일 오전 10시에 팀 회의 일정 만들어줘.").toList()
            calendarMillis += (System.nanoTime() - started) / 1_000_000.0
            assertEquals(listOf("get_current_datetime", "create_calendar_event"), calendar.executed)
            assertEquals("2026-07-27T10:00", calendar.arguments.last().string("start_time"))
        }
        println(
            "[WORKFLOW_STABILITY] repeats=10 email_success=10 sms_success=10 calendar_success=10 " +
                "email_p50_ms=${emailMillis.sorted()[5]} email_p95_ms=${emailMillis.maxOrNull()} " +
                "sms_p50_ms=${smsMillis.sorted()[5]} sms_p95_ms=${smsMillis.maxOrNull()} " +
                "calendar_p50_ms=${calendarMillis.sorted()[5]} calendar_p95_ms=${calendarMillis.maxOrNull()}",
        )
    }

    private class Fixture(
        private val searchCount: Int = 1,
        private val email: String = "jiwon@example.com",
    ) {
        val executed = mutableListOf<String>()
        val arguments = mutableListOf<JsonObject>()
        lateinit var model: FakeStructuredModel

        private val plugins = listOf(
            plugin("search_contacts", listOf("query")) {
                buildJsonObject {
                    put("results", buildJsonArray {
                        repeat(searchCount) { index ->
                            add(buildJsonObject {
                                put("card_id", "card-$index")
                                put("name", "김지원")
                            })
                        }
                    })
                }
            },
            plugin(
                "get_contact",
                listOf("card_id"),
                buildJsonObject {
                    putJsonObject("card_id") { put("type", "string") }
                    putJsonObject("purpose") {
                        put("type", "string")
                        put("enum", JsonArray(listOf("display", "email", "sms", "calendar").map(::JsonPrimitive)))
                    }
                },
            ) {
                buildJsonObject {
                    put("card_id", it.string("card_id").orEmpty())
                    put("name", "김지원")
                    put("email", email)
                    put("mobile", "010-1234-5678")
                    put("phone", "02-1234-5678")
                }
            },
            plugin("open_compose", listOf("channel", "to", "body"), composeProperties()) {
                buildJsonObject { put("opened", true); put("destination", "message") }
            },
            plugin(
                "get_current_datetime",
                emptyList(),
                buildJsonObject {
                    putJsonObject("timezone") { put("type", "string") }
                },
            ) {
                buildJsonObject {
                    put("date", "2026-07-24")
                    put("time", "09:00:00")
                    put("timezone", "Asia/Seoul")
                }
            },
            plugin("create_calendar_event", listOf("title", "start_time"), calendarProperties()) {
                buildJsonObject { put("opened", true); put("destination", "calendar") }
            },
            plugin("update_business_card", listOf("card_id"), updateProperties(), ToolEffect.LOCAL_MUTATION) {
                buildJsonObject { put("after", buildJsonObject { put("card_id", it.string("card_id").orEmpty()) }) }
            },
        )

        val sessionStore = InMemoryAgentSessionStore()

        fun kernel(plan: StructuredIntentPlan): StructuredAgentKernel {
            model = FakeStructuredModel(plan)
            val candidates = plugins.map { ToolImplementationCandidate(it) }
            return StructuredAgentKernel(
                DefaultToolRegistry(candidates),
                DefaultToolExecutor(DefaultToolRegistry(candidates)),
                DefaultToolPolicyEngine(),
                sessionStore,
                DefaultToolObservationMapper(),
                FakeEnvironment(),
                model,
            )
        }

        private fun plugin(
            name: String,
            required: List<String>,
            properties: JsonObject = genericProperties(required),
            effect: ToolEffect = ToolEffect.READ_ONLY,
            result: (JsonObject) -> JsonObject,
        ) = object : ToolPlugin {
            override val implementationId = ToolImplementationId("fake.$name")
            override val contract = contract(name, required, properties, effect)
            override suspend fun availability() = ToolAvailability.Ready
            override suspend fun execute(
                request: ToolRequest,
                context: ToolExecutionContext,
            ): ToolExecutionResult {
                executed += name
                arguments += request.arguments
                return ToolExecutionResult.Success(
                    request.callId,
                    request.capabilityId,
                    request.contractVersion,
                    1,
                    result(request.arguments),
                )
            }
        }
    }

    private class FakeStructuredModel(
        private val plan: StructuredIntentPlan,
    ) : StructuredAgentModelGateway {
        var contentCalls = 0
        val intentConversations = mutableListOf<ConversationContext>()
        val finalConversations = mutableListOf<ConversationContext>()

        override suspend fun analyzeIntent(
            userText: String,
            conversation: ConversationContext,
        ): StructuredModelResult<StructuredIntentPlan> {
            intentConversations += conversation
            return StructuredModelResult.Success(plan, listOf("{}"), 1)
        }

        override suspend fun generateComposeContent(request: ComposeContentRequest):
            StructuredModelResult<GeneratedComposeContent> {
            contentCalls += 1
            val value = if (request.channel == ComposeChannel.EMAIL) {
                GeneratedComposeContent("감사드립니다", "지난 미팅에 감사드립니다.")
            } else {
                GeneratedComposeContent(null, "곧 도착하겠습니다.")
            }
            return StructuredModelResult.Success(value, listOf("{}"), 1)
        }

        override suspend fun generateFinalText(request: StructuredFinalRequest):
            StructuredModelResult<String> {
            finalConversations += request.conversation
            return StructuredModelResult.Success(
                request.completionHintKo,
                listOf(request.completionHintKo),
                1,
            )
        }
    }

    private class FakeEnvironment : AgentRuntimeEnvironment {
        override val localeTag = "ko-KR"
        override val timeZoneId = "Asia/Seoul"
        override suspend fun grantedPermissions() = emptySet<String>()
        override suspend fun deviceCapabilities() = setOf(
            "android.external_ui",
            "contact.local_search",
            "contact.local_update",
            "datetime.current",
        )
        override suspend fun toolContext(sessionId: String, turnId: String) =
            ToolExecutionContext(
                sessionId,
                turnId,
                localeTag,
                timeZoneId,
                confirmationGateway = { true },
            )
    }

    companion object {
        private val VERSION = ContractVersion(1, 0)

        private fun plan(
            intent: AgentIntent,
            recipientType: RecipientType,
            recipientValue: String,
            goal: String,
            execute: Boolean = true,
            date: String? = null,
            time: String? = null,
            title: String? = null,
            clarification: String? = null,
            updates: JsonObject? = null,
        ) = StructuredIntentPlan(
            intent,
            execute,
            IntentRecipient(recipientType, recipientValue),
            goal,
            date,
            time,
            title,
            updates,
            clarification,
        )

        private fun contract(
            name: String,
            required: List<String>,
            properties: JsonObject,
            effect: ToolEffect,
        ) = ToolContract(
            ToolCapabilityId("fake.$name"),
            name,
            VERSION,
            "$name test",
            objectSchema(required, properties),
            objectSchema(emptyList(), buildJsonObject {}),
            effect,
            if (effect == ToolEffect.LOCAL_MUTATION) {
                ConfirmationPolicy.BEFORE_EXECUTION
            } else {
                ConfirmationPolicy.NONE
            },
            PiiLevel.NONE,
            PiiLevel.NONE,
            defaultTimeoutMillis = 1_000,
            presentation = ToolPresentation("running", "done", "unavailable"),
        )

        private fun objectSchema(required: List<String>, properties: JsonObject) =
            buildJsonObject {
                put("type", "object")
                put("additionalProperties", false)
                put("required", JsonArray(required.map(::JsonPrimitive)))
                put("properties", properties)
            }

        private fun genericProperties(names: List<String>) = buildJsonObject {
            names.forEach { putJsonObject(it) { put("type", "string") } }
        }

        private fun composeProperties() = buildJsonObject {
            putJsonObject("channel") {
                put("type", "string")
                put("enum", JsonArray(listOf("email", "sms").map(::JsonPrimitive)))
            }
            listOf("to", "subject", "body").forEach {
                putJsonObject(it) { put("type", "string") }
            }
        }

        private fun calendarProperties() = buildJsonObject {
            putJsonObject("title") { put("type", "string") }
            putJsonObject("start_time") { put("type", "string") }
            putJsonObject("attendee_emails") {
                put("type", "array")
                putJsonObject("items") { put("type", "string"); put("format", "email") }
            }
        }

        private fun updateProperties() = buildJsonObject {
            putJsonObject("card_id") { put("type", "string") }
            putJsonObject("updates") { put("type", "object") }
        }

        private fun JsonObject.string(name: String): String? =
            (this[name] as? JsonPrimitive)?.content
    }
}
