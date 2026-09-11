package com.hjp.tool.datetime

import com.hjp.tool.contract.DecodeResult
import com.hjp.tool.contract.StandardToolErrorCodes
import com.hjp.tool.contract.ToolAvailability
import com.hjp.tool.contract.ToolError
import com.hjp.tool.contract.ToolExecutionContext
import com.hjp.tool.contract.ToolImplementationId
import com.hjp.tool.contract.ToolInputCodec
import com.hjp.tool.contract.ToolOutputCodec
import com.hjp.tool.contract.ToolRequest
import com.hjp.tool.contract.TypedToolPlugin
import com.hjp.tool.contract.TypedToolResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class GetCurrentDateTimeInput(val timezone: String?)

data class CurrentDateTimeOutput(
    val date: String,
    val time: String,
    val datetime: String,
    val timezone: String,
    val epochMillis: Long,
    val utcOffset: String,
)

private fun JsonObject.optionalString(key: String) =
    (this[key] as? JsonPrimitive)?.content?.trim()?.takeIf(String::isNotEmpty)

private object CurrentDateTimeInputCodec : ToolInputCodec<GetCurrentDateTimeInput> {
    override val schema = CURRENT_DATETIME_INPUT_SCHEMA

    override fun decode(arguments: JsonObject): DecodeResult<GetCurrentDateTimeInput> =
        DecodeResult.Success(GetCurrentDateTimeInput(arguments.optionalString("timezone")))
}

private object CurrentDateTimeOutputCodec : ToolOutputCodec<CurrentDateTimeOutput> {
    override val schema = CURRENT_DATETIME_OUTPUT_SCHEMA

    override fun encode(value: CurrentDateTimeOutput) = buildJsonObject {
        put("date", value.date)
        put("time", value.time)
        put("datetime", value.datetime)
        put("timezone", value.timezone)
        put("epoch_millis", value.epochMillis)
        put("utc_offset", value.utcOffset)
    }
}

class GetCurrentDateTimePlugin(
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : TypedToolPlugin<GetCurrentDateTimeInput, CurrentDateTimeOutput>(
    ToolImplementationId("datetime.current.device.v1"),
    DateTimeToolContracts.Current,
    CurrentDateTimeInputCodec,
    CurrentDateTimeOutputCodec,
) {
    override suspend fun availability(): ToolAvailability = ToolAvailability.Ready

    override suspend fun executeTyped(
        input: GetCurrentDateTimeInput,
        request: ToolRequest,
        context: ToolExecutionContext,
    ): TypedToolResult<CurrentDateTimeOutput> {
        val timezone = input.timezone?.let { requested ->
            if (TimeZone.getAvailableIDs().none { it == requested }) {
                return TypedToolResult.Failure(ToolError(
                    StandardToolErrorCodes.INVALID_ARGUMENTS,
                    "알 수 없는 timezone입니다: $requested",
                    retryable = false,
                ))
            }
            TimeZone.getTimeZone(requested)
        } ?: TimeZone.getTimeZone(context.deviceTimeZoneId)

        val now = Date(clockMillis())
        fun format(pattern: String) = SimpleDateFormat(pattern, Locale.US).apply {
            timeZone = timezone
        }.format(now)

        return TypedToolResult.Success(CurrentDateTimeOutput(
            date = format("yyyy-MM-dd"),
            time = format("HH:mm:ss"),
            datetime = format("yyyy-MM-dd'T'HH:mm:ssXXX"),
            timezone = timezone.id,
            epochMillis = now.time,
            utcOffset = offsetFor(timezone, now),
        ))
    }

    private fun offsetFor(timezone: TimeZone, date: Date): String {
        val totalMinutes = timezone.getOffset(date.time) / 60_000
        val sign = if (totalMinutes >= 0) "+" else "-"
        val absMinutes = kotlin.math.abs(totalMinutes)
        return "%s%02d:%02d".format(Locale.US, sign, absMinutes / 60, absMinutes % 60)
    }
}
