package com.example.hjp.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 인식기 charset 의 마지막 칸은 **공백**이어야 한다.
 *
 * 사전 파일이 개행으로 끝나는데 Kotlin 의 `lineSequence()` 는 그 뒤의 빈 문자열도 한 항목으로
 * 내놓는다 — 파이썬 `splitlines()` 는 그러지 않는다. 그 한 칸 차이가 공백 문자의 번호를
 * 밀어내서, 인식된 공백이 전부 빈 문자열이 됐다:
 *
 * ```
 *   참조 구현:  "경기도 성남시 분당구 판교역로 123"
 *   이식본:     "경기도성남시분당구판교역로123"
 * ```
 *
 * 눈으로는 사소해 보이지만 FTS 는 공백에서 자른다. 촬영으로 저장한 명함의 주소가 통째로
 * 낱말 하나가 되어, 나중에 "판교" 로도 "성남" 으로도 안 잡힌다.
 */
class CharsetTest {

    private val dict: String = java.io.File("../app/src/main/assets/ocr/korean_dict.txt")
        .takeIf { it.isFile }?.readText() ?: ""

    /** [OcrPipeline] 의 init 과 같은 규칙. 거기 것이 정본이고 여기서 규칙만 확인한다. */
    private fun buildCharset(raw: String): List<String> = buildList {
        add("<blank>")
        raw.lineSequence().forEach { line -> if (line.isNotEmpty()) add(line) }
        add(" ")
    }

    @Test
    fun `a trailing newline does not shift the space entry`() {
        val charset = buildCharset("가\r\n나\r\n")
        assertEquals(listOf("<blank>", "가", "나", " "), charset)
    }

    @Test
    fun `unix line endings behave the same`() {
        assertEquals(listOf("<blank>", "가", "나", " "), buildCharset("가\n나\n"))
    }

    @Test
    fun `the shipped dictionary puts space last and nowhere else`() {
        if (dict.isEmpty()) return
        val charset = buildCharset(dict)
        assertEquals(" ", charset.last())
        // 사전 줄 수 + blank + space. 빈 항목이 하나라도 끼면 여기서 어긋난다.
        assertEquals(dict.lineSequence().count { it.isNotEmpty() } + 2, charset.size)
        assertEquals(0, charset.drop(1).dropLast(1).count { it.isEmpty() })
    }
}
