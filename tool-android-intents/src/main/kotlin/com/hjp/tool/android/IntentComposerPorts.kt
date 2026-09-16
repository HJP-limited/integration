package com.hjp.tool.android

/** Platform-independent drafts and ports; shared by Android and the explicit desktop simulator. */
data class CalendarDraft(
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val location: String?,
    val description: String?,
    val attendeeEmails: List<String>,
)

data class MessageDraft(val channel: MessageChannel, val to: String, val subject: String?, val body: String)
enum class MessageChannel { EMAIL, SMS }

interface CalendarComposerBackend {
    fun isAvailable(): Boolean
    suspend fun open(draft: CalendarDraft): Boolean
}

interface MessageComposerBackend {
    fun isAvailable(channel: MessageChannel? = null): Boolean
    suspend fun open(draft: MessageDraft): Boolean
}
