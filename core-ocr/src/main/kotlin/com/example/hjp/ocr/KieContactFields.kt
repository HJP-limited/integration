package com.example.hjp.ocr

/** Correct only unambiguous email/URL shapes after the required KIE classifier has run. */
internal object KieContactFields {
    private val email = Regex("""[\w.+-]+@[\w-]+(\.[\w-]+)+""")
    // A label requires a separator: the first W of www.example.com is part of the URL.
    private val prefix = Regex(
        """(?i)^\s*(?:Website|Homepage|Web|W|E-?mail|E)(?:\s*[.:]\s*|\s+)""",
    )
    private val explicitUrl = Regex("""(?i)^(?:https?://|www\.)\S+$""")

    fun normalize(field: String, raw: String): Pair<String, String> {
        if (field != "email" && field != "website") return field to raw
        val value = raw.replaceFirst(prefix, "").trim()
        email.matchEntire(value)?.value?.let { return "email" to it }
        if (explicitUrl.matches(value)) return "website" to value
        email.find(value)?.value?.let { return "email" to it }
        return field to value
    }
}
