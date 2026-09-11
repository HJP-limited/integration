package com.hjp.tool.android

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract

data class CalendarDraft(
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val location: String?,
    val description: String?,
    val attendeeEmails: List<String>,
)

data class MessageDraft(
    val channel: MessageChannel,
    val to: String,
    val subject: String?,
    val body: String,
)

enum class MessageChannel { EMAIL, SMS }

interface CalendarComposerBackend {
    fun isAvailable(): Boolean
    suspend fun open(draft: CalendarDraft): Boolean
}

interface MessageComposerBackend {
    fun isAvailable(channel: MessageChannel? = null): Boolean
    suspend fun open(draft: MessageDraft): Boolean
}

class AndroidCalendarComposerBackend(context: Context) : CalendarComposerBackend {
    private val appContext = context.applicationContext

    override fun isAvailable(): Boolean = buildProbeIntent().resolveActivity(appContext.packageManager) != null

    override suspend fun open(draft: CalendarDraft): Boolean {
        val intent = buildProbeIntent().apply {
            putExtra(CalendarContract.Events.TITLE, draft.title)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, draft.startMillis)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, draft.endMillis)
            draft.location?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
            draft.description?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) }
            if (draft.attendeeEmails.isNotEmpty()) {
                putExtra(Intent.EXTRA_EMAIL, draft.attendeeEmails.joinToString(","))
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            appContext.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun buildProbeIntent() = Intent(Intent.ACTION_INSERT).apply {
        setDataAndType(CalendarContract.Events.CONTENT_URI, "vnd.android.cursor.dir/event")
    }
}

class AndroidMessageComposerBackend(context: Context) : MessageComposerBackend {
    private val appContext = context.applicationContext

    override fun isAvailable(channel: MessageChannel?): Boolean = when (channel) {
        MessageChannel.EMAIL -> buildEmailIntent("", "", "").resolveActivity(appContext.packageManager) != null
        MessageChannel.SMS -> buildSmsIntent("", "").resolveActivity(appContext.packageManager) != null
        null -> isAvailable(MessageChannel.EMAIL) || isAvailable(MessageChannel.SMS)
    }

    override suspend fun open(draft: MessageDraft): Boolean {
        val intent = when (draft.channel) {
            MessageChannel.EMAIL -> buildEmailIntent(draft.to, draft.subject.orEmpty(), draft.body)
            MessageChannel.SMS -> buildSmsIntent(draft.to, draft.body)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            appContext.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun buildEmailIntent(to: String, subject: String, body: String) =
        Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:${Uri.encode(to)}?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }

    private fun buildSmsIntent(to: String, body: String) =
        Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(to)}")).apply {
            putExtra("sms_body", body)
        }
}
