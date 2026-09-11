package com.hjp.tool.android

import com.hjp.tool.contract.DecodeResult
import com.hjp.tool.contract.ToolAvailability
import com.hjp.tool.contract.ToolError
import com.hjp.tool.contract.ToolErrorCode
import com.hjp.tool.contract.ToolExecutionContext
import com.hjp.tool.contract.ToolImplementationId
import com.hjp.tool.contract.ToolInputCodec
import com.hjp.tool.contract.ToolOutputCodec
import com.hjp.tool.contract.ToolRequest
import com.hjp.tool.contract.TypedToolPlugin
import com.hjp.tool.contract.TypedToolResult
import java.text.ParseException
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class CreateCalendarEventInput(
    val title: String, val startTime: String, val endTime: String?, val location: String?,
    val description: String?, val attendeeEmails: List<String>,
)
data class OpenComposeInput(val channel: MessageChannel, val to: String, val subject: String?, val body: String)
data class ExternalUiOpened(val opened: Boolean, val destination: String)

private fun JsonObject.optionalString(key: String) = (this[key] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }

private object CalendarInputCodec : ToolInputCodec<CreateCalendarEventInput> {
    override val schema = CALENDAR_INPUT_SCHEMA
    override fun decode(arguments: JsonObject): DecodeResult<CreateCalendarEventInput> {
        val title = arguments.optionalString("title") ?: return DecodeResult.Failure("일정 제목이 필요합니다.", "title")
        val start = arguments.optionalString("start_time") ?: return DecodeResult.Failure("시작 시각이 필요합니다.", "start_time")
        val attendees = (arguments["attendee_emails"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf(String::isNotEmpty) }.orEmpty()
        if (attendees.any { !isValidEmail(it) }) {
            return DecodeResult.Failure("참석자 이메일 주소 형식이 올바르지 않습니다.", "attendee_emails")
        }
        return DecodeResult.Success(CreateCalendarEventInput(title, start, arguments.optionalString("end_time"),
            arguments.optionalString("location"), arguments.optionalString("description"), attendees))
    }
}

private object ComposeInputCodec : ToolInputCodec<OpenComposeInput> {
    override val schema = COMPOSE_INPUT_SCHEMA
    override fun decode(arguments: JsonObject): DecodeResult<OpenComposeInput> {
        val channel = when (arguments.optionalString("channel")?.lowercase()) {
            "email" -> MessageChannel.EMAIL
            "sms" -> MessageChannel.SMS
            else -> return DecodeResult.Failure("channel은 email 또는 sms여야 합니다.", "channel")
        }
        val to = arguments.optionalString("to") ?: return DecodeResult.Failure("받는 사람의 주소가 필요합니다.", "to")
        val body = arguments.optionalString("body")
            ?: return DecodeResult.Failure("메시지 본문이 필요합니다.", "body")
        val subject = arguments.optionalString("subject")
        when (channel) {
            MessageChannel.EMAIL -> {
                if (!isValidEmail(to)) return DecodeResult.Failure("이메일 주소 형식이 올바르지 않습니다.", "to")
                if (subject == null) return DecodeResult.Failure("이메일 제목이 필요합니다.", "subject")
            }
            MessageChannel.SMS -> {
                if (!isValidPhone(to)) return DecodeResult.Failure("전화번호 형식이 올바르지 않습니다.", "to")
                if (subject != null) return DecodeResult.Failure("문자에는 제목을 넣을 수 없습니다.", "subject")
            }
        }
        return DecodeResult.Success(OpenComposeInput(channel, to, subject, body))
    }
}

private object ExternalUiOutputCodec : ToolOutputCodec<ExternalUiOpened> {
    override val schema = EXTERNAL_UI_OUTPUT_SCHEMA
    override fun encode(value: ExternalUiOpened) = buildJsonObject {
        put("opened", value.opened); put("destination", value.destination); put("requires_user_confirmation", true)
    }
}

class CreateCalendarEventPlugin(private val backend: CalendarComposerBackend) :
    TypedToolPlugin<CreateCalendarEventInput, ExternalUiOpened>(
        ToolImplementationId("android.calendar.intent.v1"), AndroidIntentToolContracts.Calendar,
        CalendarInputCodec, ExternalUiOutputCodec,
    ) {
    override suspend fun availability() = if (backend.isAvailable()) ToolAvailability.Ready
        else ToolAvailability.Unavailable("android.calendar_app_not_found")

    override suspend fun executeTyped(input: CreateCalendarEventInput, request: ToolRequest, context: ToolExecutionContext): TypedToolResult<ExternalUiOpened> {
        val start = parseLocal(input.startTime, context.deviceTimeZoneId)
            ?: return invalid("start_time 형식은 yyyy-MM-dd'T'HH:mm이어야 합니다.")
        val end = input.endTime?.let { parseLocal(it, context.deviceTimeZoneId) }
            ?: if (input.endTime == null) start + 3_600_000L else return invalid("end_time 형식이 올바르지 않습니다.")
        if (end <= start) return invalid("종료 시각은 시작 시각보다 늦어야 합니다.")
        val opened = backend.open(CalendarDraft(input.title, start, end, input.location, input.description, input.attendeeEmails))
        return if (opened) TypedToolResult.Success(ExternalUiOpened(true, "calendar"))
        else externalAppFailure("캘린더 앱을 열 수 없습니다.")
    }
}

class OpenComposePlugin(private val backend: MessageComposerBackend) :
    TypedToolPlugin<OpenComposeInput, ExternalUiOpened>(
        ToolImplementationId("android.message.intent.v1"), AndroidIntentToolContracts.Compose,
        ComposeInputCodec, ExternalUiOutputCodec,
    ) {
    override suspend fun availability() = if (backend.isAvailable()) ToolAvailability.Ready
        else ToolAvailability.Unavailable("android.compose_app_not_found")

    override suspend fun executeTyped(input: OpenComposeInput, request: ToolRequest, context: ToolExecutionContext): TypedToolResult<ExternalUiOpened> {
        if (!backend.isAvailable(input.channel)) return externalAppFailure("해당 작성 앱이 없습니다.")
        val opened = backend.open(MessageDraft(input.channel, input.to, input.subject, input.body))
        return if (opened) TypedToolResult.Success(ExternalUiOpened(true, input.channel.name.lowercase()))
        else externalAppFailure("작성 화면을 열 수 없습니다.")
    }
}

/**
 * The plugin's own datetime contract: exactly `yyyy-MM-dd'T'HH:mm`, whole string, nothing else.
 *
 * It used to try `…:ss` first and call `SimpleDateFormat.parse(String)`, which stops at the first
 * character it cannot use. Together that accepted a non-zero seconds component (`T16:00:30`, a
 * different instant from what the caller wrote), a zone suffix, and any trailing text at all —
 * `2027-05-06T16:00 그리고 회의` parsed happily as 16:00.
 *
 * The kernel canonicalises a zero-seconds value to minute precision before dispatch, so the ordinary
 * path is unaffected; this is the defence for a caller that reaches the plugin directly, which gets
 * no such help. One accepted form, stated once.
 */
private val CANONICAL_LOCAL_DATETIME = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}""")

private fun parseLocal(value: String, timeZoneId: String): Long? {
    // `matches` is whole-string, so a valid prefix is not a valid value.
    if (!CANONICAL_LOCAL_DATETIME.matches(value)) return null
    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply {
        isLenient = false
        timeZone = TimeZone.getTimeZone(timeZoneId)
    }
    val position = ParsePosition(0)
    val parsed = format.parse(value, position) ?: return null
    // Belt and braces: `isLenient = false` rejects 2027-02-30 and 25:00, and this rejects anything
    // the formatter silently declined to consume.
    if (position.index != value.length || position.errorIndex >= 0) return null
    return parsed.time
}

private val EMAIL_REGEX =
    Regex("""(?i)\b[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?(?:\.[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?)+(?![A-Z0-9.-])""")
private val PHONE_REGEX = Regex("""(?<!\d)(?:\+82[- ]?|0)\d{1,2}[- ]?\d{3,4}[- ]?\d{4}(?!\d)""")

private fun isValidEmail(value: String): Boolean = EMAIL_REGEX.matches(value.trim())

private fun isValidPhone(value: String): Boolean = PHONE_REGEX.matches(value.trim())

private fun <O : Any> invalid(message: String): TypedToolResult<O> = TypedToolResult.Failure(
    ToolError(com.hjp.tool.contract.StandardToolErrorCodes.INVALID_ARGUMENTS, message, false)
)

private fun <O : Any> externalAppFailure(message: String): TypedToolResult<O> = TypedToolResult.Failure(
    ToolError(ToolErrorCode("android.external_app_not_found"), message, false)
)
