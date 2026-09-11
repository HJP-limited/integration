package com.hjp.tool.datetime

import com.hjp.tool.contract.ConfirmationPolicy
import com.hjp.tool.contract.ContractVersion
import com.hjp.tool.contract.PiiLevel
import com.hjp.tool.contract.ToolCapabilityId
import com.hjp.tool.contract.ToolContract
import com.hjp.tool.contract.ToolEffect
import com.hjp.tool.contract.ToolPresentation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private fun stringProperty(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun integerProperty(description: String) = buildJsonObject {
    put("type", "integer")
    put("description", description)
}

private fun objectSchema(
    required: List<String>,
    properties: kotlinx.serialization.json.JsonObject,
) = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
    put("properties", properties)
    put("required", JsonArray(required.map(::JsonPrimitive)))
}

internal val CURRENT_DATETIME_INPUT_SCHEMA = objectSchema(emptyList(), buildJsonObject {
    put("timezone", stringProperty("선택 IANA timezone ID. 예: Asia/Seoul. 생략 시 기기 기본 timezone"))
})

internal val CURRENT_DATETIME_OUTPUT_SCHEMA = objectSchema(
    listOf("date", "time", "datetime", "timezone", "epoch_millis", "utc_offset"),
    buildJsonObject {
        put("date", stringProperty("yyyy-MM-dd 날짜"))
        put("time", stringProperty("HH:mm:ss 시각"))
        put("datetime", stringProperty("ISO 8601 offset 포함 날짜시각"))
        put("timezone", stringProperty("적용된 IANA timezone ID"))
        put("epoch_millis", integerProperty("Unix epoch milliseconds"))
        put("utc_offset", stringProperty("+09:00 형식 UTC offset"))
    },
)

object DateTimeToolContracts {
    val Current = ToolContract(
        capabilityId = ToolCapabilityId("datetime.current"),
        modelName = "get_current_datetime",
        version = ContractVersion(1, 0),
        description = "기기 또는 지정 timezone 기준 현재 날짜와 시각을 반환합니다. 오늘, 내일, 다음 주 같은 상대 날짜를 절대 시각으로 바꾸기 전에 사용하세요.",
        inputSchema = CURRENT_DATETIME_INPUT_SCHEMA,
        outputSchema = CURRENT_DATETIME_OUTPUT_SCHEMA,
        effect = ToolEffect.READ_ONLY,
        confirmationPolicy = ConfirmationPolicy.NONE,
        inputPii = PiiLevel.NONE,
        outputPii = PiiLevel.NONE,
        defaultTimeoutMillis = 1_000,
        presentation = ToolPresentation("현재 날짜와 시각을 확인하고 있어요.", "현재 날짜와 시각을 확인했어요.", "현재 날짜와 시각을 확인할 수 없어요."),
    )
}
