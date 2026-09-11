package com.hjp.tool.datetime

import com.hjp.tool.contract.ToolExecutionContext
import com.hjp.tool.contract.ToolExecutionResult
import com.hjp.tool.contract.ToolRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DateTimePluginsTest {
    @Test
    fun `returns current date time in device timezone`() = runBlocking {
        val result = GetCurrentDateTimePlugin(clockMillis = { 0L }).execute(
            ToolRequest(
                "1",
                DateTimeToolContracts.Current.capabilityId,
                DateTimeToolContracts.Current.version,
                buildJsonObject { },
            ),
            ToolExecutionContext("s", "t", "ko-KR", "Asia/Seoul"),
        ) as ToolExecutionResult.Success

        assertEquals("1970-01-01", (result.data["date"] as JsonPrimitive).content)
        assertEquals("09:00:00", (result.data["time"] as JsonPrimitive).content)
        assertEquals("Asia/Seoul", (result.data["timezone"] as JsonPrimitive).content)
        assertEquals("+09:00", (result.data["utc_offset"] as JsonPrimitive).content)
    }

    @Test
    fun `rejects unknown timezone`() = runBlocking {
        val result = GetCurrentDateTimePlugin(clockMillis = { 0L }).execute(
            ToolRequest(
                "1",
                DateTimeToolContracts.Current.capabilityId,
                DateTimeToolContracts.Current.version,
                buildJsonObject { put("timezone", "Not/AZone") },
            ),
            ToolExecutionContext("s", "t", "ko-KR", "Asia/Seoul"),
        )

        assertTrue(result is ToolExecutionResult.Failure)
    }
}
