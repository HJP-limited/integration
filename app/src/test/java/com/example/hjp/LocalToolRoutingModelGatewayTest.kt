package com.example.hjp

import com.hjp.agent.contract.ModelConversationRole
import com.hjp.agent.contract.TranscriptEntry
import com.hjp.agent.contract.TurnContext
import com.hjp.agent.contract.ModelDecision
import com.hjp.agent.contract.ModelInput
import com.hjp.agent.contract.ModelSessionConfig
import com.hjp.agent.contract.ModelToolResponse
import com.hjp.tool.android.AndroidIntentToolContracts
import com.hjp.tool.contact.ContactToolContracts
import com.hjp.tool.contract.ToolContract
import com.hjp.tool.contract.ToolCatalogSnapshot
import com.hjp.tool.datetime.DateTimeToolContracts
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalToolRoutingModelGatewayTest {
    @Test
    fun `contact request produces search tool call and consumes its result`() = runBlocking {
        val session = session(ContactToolContracts.Search)

        val decision = session.decide(ModelInput.User("김민수 명함 찾아줘.")) as ModelDecision.ToolCalls
        val call = decision.calls.single()
        assertEquals("search_contacts", call.modelToolName)
        assertEquals("김민수", (call.arguments["query"] as JsonPrimitive).content)

        val final = session.continueWithToolResult(ModelToolResponse(
            call.callId,
            call.modelToolName,
            buildJsonObject {
                put("ok", true)
                put("data", buildJsonObject {
                    put("results", buildJsonArray { })
                    put("count", 0)
                })
            },
        )) as ModelDecision.FinalCandidate
        assertEquals("‘김민수’에 해당하는 명함을 찾지 못했습니다.", final.draftText)
    }

    @Test
    fun `calendar request produces create calendar event tool call`() = runBlocking {
        val session = session(AndroidIntentToolContracts.Calendar)

        val decision = session.decide(ModelInput.User("2026년 7월 10일 오후 2시 회의 일정 만들어줘.")) as ModelDecision.ToolCalls
        val call = decision.calls.single()

        assertEquals("create_calendar_event", call.modelToolName)
        assertEquals("회의", (call.arguments["title"] as JsonPrimitive).content)
        assertEquals("2026-07-10T14:00", (call.arguments["start_time"] as JsonPrimitive).content)

        val final = session.continueWithToolResult(externalUiSuccess(call.callId, call.modelToolName, "calendar"))
            as ModelDecision.FinalCandidate
        assertEquals("캘린더 작성 화면을 열었습니다. 저장 전에 확인해 주세요.", final.draftText)
    }

    @Test
    fun `relative calendar request checks current date before calendar tool call`() = runBlocking {
        val session = session(DateTimeToolContracts.Current, AndroidIntentToolContracts.Calendar)

        val dateDecision = session.decide(ModelInput.User("내일 오후 2시 회의 일정 만들어줘."))
            as ModelDecision.ToolCalls
        val dateCall = dateDecision.calls.single()
        assertEquals("get_current_datetime", dateCall.modelToolName)

        val calendarDecision = session.continueWithToolResult(currentDateSuccess(dateCall.callId, dateCall.modelToolName))
            as ModelDecision.ToolCalls
        val calendarCall = calendarDecision.calls.single()

        assertEquals("create_calendar_event", calendarCall.modelToolName)
        assertEquals("회의", (calendarCall.arguments["title"] as JsonPrimitive).content)
        assertEquals("2026-07-11T14:00", (calendarCall.arguments["start_time"] as JsonPrimitive).content)
    }

    @Test
    fun `current datetime request produces current datetime tool call and final text`() = runBlocking {
        val session = session(DateTimeToolContracts.Current)

        val decision = session.decide(ModelInput.User("현재 시간 알려줘."))
            as ModelDecision.ToolCalls
        val call = decision.calls.single()
        assertEquals("get_current_datetime", call.modelToolName)

        val final = session.continueWithToolResult(currentDateSuccess(call.callId, call.modelToolName))
            as ModelDecision.FinalCandidate
        assertTrue(final.draftText.contains("2026-07-10"))
        assertTrue(final.draftText.contains("Asia/Seoul"))
    }

    @Test
    fun `email request produces compose tool call`() = runBlocking {
        val session = session(AndroidIntentToolContracts.Compose)

        val decision = session.decide(
            ModelInput.User("jiwon@example.com에게 제목은 회의 요청, 내용은 내일 가능하신가요 라고 메일 작성해줘."),
        ) as ModelDecision.ToolCalls
        val call = decision.calls.single()

        assertEquals("open_compose", call.modelToolName)
        assertEquals("email", (call.arguments["channel"] as JsonPrimitive).content)
        assertEquals("jiwon@example.com", (call.arguments["to"] as JsonPrimitive).content)
        assertEquals("회의 요청", (call.arguments["subject"] as JsonPrimitive).content)
        assertEquals("내일 가능하신가요", (call.arguments["body"] as JsonPrimitive).content)
    }

    @Test
    fun `email request without body still produces compose tool call`() = runBlocking {
        val session = session(AndroidIntentToolContracts.Compose)

        val decision = session.decide(ModelInput.User("test@example.com에게 메일 작성해줘."))
            as ModelDecision.ToolCalls
        val call = decision.calls.single()

        assertEquals("open_compose", call.modelToolName)
        assertEquals("email", (call.arguments["channel"] as JsonPrimitive).content)
        assertEquals("test@example.com", (call.arguments["to"] as JsonPrimitive).content)
        assertEquals("", (call.arguments["body"] as JsonPrimitive).content)
    }

    @Test
    fun `sms request produces compose tool call`() = runBlocking {
        val session = session(AndroidIntentToolContracts.Compose)

        val decision = session.decide(ModelInput.User("010-0000-0001로 회의에 늦는다고 문자 보내줘."))
            as ModelDecision.ToolCalls
        val call = decision.calls.single()

        assertEquals("open_compose", call.modelToolName)
        assertEquals("sms", (call.arguments["channel"] as JsonPrimitive).content)
        assertEquals("010-0000-0001", (call.arguments["to"] as JsonPrimitive).content)
        assertEquals("회의에 늦는다고", (call.arguments["body"] as JsonPrimitive).content)
    }

    @Test
    fun `contact email request searches gets contact and opens compose`() = runBlocking {
        val session = session(
            ContactToolContracts.Search,
            ContactToolContracts.Get,
            AndroidIntentToolContracts.Compose,
        )

        val searchDecision = session.decide(ModelInput.User("김지원에게 내용은 안녕하세요 라고 메일 작성해줘."))
            as ModelDecision.ToolCalls
        val searchCall = searchDecision.calls.single()
        assertEquals("search_contacts", searchCall.modelToolName)
        assertEquals("김지원", (searchCall.arguments["query"] as JsonPrimitive).content)

        val getDecision = session.continueWithToolResult(searchSuccess(searchCall.callId, searchCall.modelToolName))
            as ModelDecision.ToolCalls
        val getCall = getDecision.calls.single()
        assertEquals("get_contact", getCall.modelToolName)
        assertEquals("C001", (getCall.arguments["card_id"] as JsonPrimitive).content)
        assertEquals("email", (getCall.arguments["purpose"] as JsonPrimitive).content)

        val composeDecision = session.continueWithToolResult(contactSuccess(getCall.callId, getCall.modelToolName))
            as ModelDecision.ToolCalls
        val composeCall = composeDecision.calls.single()
        assertEquals("open_compose", composeCall.modelToolName)
        assertEquals("email", (composeCall.arguments["channel"] as JsonPrimitive).content)
        assertEquals("jiwon@example.com", (composeCall.arguments["to"] as JsonPrimitive).content)
        assertEquals("안녕하세요", (composeCall.arguments["body"] as JsonPrimitive).content)
    }

    @Test
    fun `contact update request searches gets contact and updates business card`() = runBlocking {
        val session = session(
            ContactToolContracts.Search,
            ContactToolContracts.Get,
            ContactToolContracts.Update,
        )

        val searchDecision = session.decide(ModelInput.User("김지원 명함 메모를 VIP로 수정해줘."))
            as ModelDecision.ToolCalls
        val searchCall = searchDecision.calls.single()
        assertEquals("search_contacts", searchCall.modelToolName)
        assertEquals("김지원", (searchCall.arguments["query"] as JsonPrimitive).content)

        val getDecision = session.continueWithToolResult(searchSuccess(searchCall.callId, searchCall.modelToolName))
            as ModelDecision.ToolCalls
        val getCall = getDecision.calls.single()
        assertEquals("get_contact", getCall.modelToolName)
        assertEquals("C001", (getCall.arguments["card_id"] as JsonPrimitive).content)

        val updateDecision = session.continueWithToolResult(contactSuccess(getCall.callId, getCall.modelToolName))
            as ModelDecision.ToolCalls
        val updateCall = updateDecision.calls.single()
        assertEquals("update_business_card", updateCall.modelToolName)
        assertEquals("C001", (updateCall.arguments["card_id"] as JsonPrimitive).content)
        val updates = updateCall.arguments["updates"] as JsonObject
        assertEquals("VIP", (updates["memo"] as JsonPrimitive).content)

        val final = session.continueWithToolResult(updateSuccess(updateCall.callId, updateCall.modelToolName))
            as ModelDecision.FinalCandidate
        assertEquals("명함을 수정했습니다.", final.draftText)
    }

    @Test
    fun `conversation reference is answered from this session instead of a new search`() = runBlocking {
        val session = session(ContactToolContracts.Search)

        val decision = session.decide(ModelInput.User(
            text = "방금 찾은 사람 누구야?",
            turnContext = TurnContext(
                userText = "방금 찾은 사람 누구야?",
                transcript = listOf(
                    TranscriptEntry("t1", ModelConversationRole.USER, "김지원 명함 찾아줘.", 1),
                    TranscriptEntry("t1", ModelConversationRole.ASSISTANT, "‘김지원’ 명함을 찾았습니다.", 2),
                ),
            ),
        )) as ModelDecision.FinalCandidate

        assertEquals("직전 대화에서 이렇게 안내했습니다: ‘김지원’ 명함을 찾았습니다.", decision.draftText)
    }

    @Test
    fun `conversation reference without any prior turn does not invent a result`() = runBlocking {
        val session = session(ContactToolContracts.Search)

        val decision = session.decide(ModelInput.User("방금 찾은 사람 누구야?"))
            as ModelDecision.FinalCandidate

        assertTrue(decision.draftText.startsWith("이 세션에서 아직 처리한 대화가 없습니다."))
    }

    private suspend fun session(vararg contracts: ToolContract) =
        LocalToolRoutingModelGateway().openSession(ModelSessionConfig(
            systemInstruction = "test",
            toolCatalog = ToolCatalogSnapshot(
                revision = "r1",
                bindingRevision = "b1",
                createdAtEpochMillis = 0,
                bindings = emptyList(),
                contractsByModelName = contracts.associateBy { it.modelName },
            ),
            localeTag = "ko-KR",
        ))

    private fun externalUiSuccess(callId: String, toolName: String, destination: String) = ModelToolResponse(
        callId,
        toolName,
        buildJsonObject {
            put("ok", true)
            put("data", buildJsonObject {
                put("opened", true)
                put("destination", destination)
                put("requires_user_confirmation", true)
            })
        },
    )

    private fun searchSuccess(callId: String, toolName: String) = ModelToolResponse(
        callId,
        toolName,
        buildJsonObject {
            put("ok", true)
            put("data", buildJsonObject {
                put("results", buildJsonArray {
                    add(buildJsonObject {
                        put("card_id", "C001")
                        put("name", "김지원")
                        put("company", "비전글로벌")
                        put("title", "대표이사")
                        put("location", "서울")
                    })
                })
                put("count", 1)
            })
        },
    )

    private fun currentDateSuccess(callId: String, toolName: String) = ModelToolResponse(
        callId,
        toolName,
        buildJsonObject {
            put("ok", true)
            put("data", buildJsonObject {
                put("date", "2026-07-10")
                put("time", "10:30:00")
                put("datetime", "2026-07-10T10:30:00+09:00")
                put("timezone", "Asia/Seoul")
                put("epoch_millis", 1_783_646_600_000L)
                put("utc_offset", "+09:00")
            })
        },
    )

    private fun contactSuccess(callId: String, toolName: String) = ModelToolResponse(
        callId,
        toolName,
        buildJsonObject {
            put("ok", true)
            put("data", buildJsonObject {
                put("card_id", "C001")
                put("name", "김지원")
                put("company", "비전글로벌")
                put("title", "대표이사")
                put("phone", "010-0000-0001")
                put("email", "jiwon@example.com")
            })
        },
    )

    private fun updateSuccess(callId: String, toolName: String) = ModelToolResponse(
        callId,
        toolName,
        buildJsonObject {
            put("ok", true)
            put("data", buildJsonObject {
                put("before", buildJsonObject {
                    put("card_id", "C001")
                    put("name", "김지원")
                    put("memo", "")
                })
                put("after", buildJsonObject {
                    put("card_id", "C001")
                    put("name", "김지원")
                    put("memo", "VIP")
                })
            })
        },
    )
}
