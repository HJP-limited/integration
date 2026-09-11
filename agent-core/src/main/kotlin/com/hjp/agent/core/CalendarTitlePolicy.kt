package com.hjp.agent.core

/**
 * Removes only generic calendar/UI suffixes from a model slot when the shorter
 * title is grounded verbatim in the original request. It does not invent a
 * title or classify intent.
 */
internal object CalendarTitlePolicy {
    private val genericSuffixes = listOf(
        " 캘린더 작성 단계까지 준비",
        " 캘린더 작성 단계 준비",
        " 캘린더 작성 단계",
        " 일정 작성 화면",
        " 일정 만들기",
        " 일정 생성",
        " 작성 화면",
        " 일정",
    )

    fun canonicalize(modelTitle: String?, originalUserText: String): String? {
        val title = modelTitle?.trim()?.takeIf(String::isNotEmpty) ?: return null
        genericSuffixes.forEach { suffix ->
            if (title.endsWith(suffix)) {
                val candidate = title.removeSuffix(suffix).trim()
                if (candidate.isNotEmpty() && originalUserText.contains(candidate)) {
                    return candidate
                }
            }
        }
        return title
    }
}
