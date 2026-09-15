package com.example.hjp

import com.hjp.agent.litert.NativeToolReplyTracker
import org.junit.Assert.*
import org.junit.Test

class NativeToolReplyTrackerTest {
    @Test fun `only outstanding model calls receive native tool role replies`() {
        val tracker = NativeToolReplyTracker()
        assertFalse(tracker.consume("get_contact"))
        tracker.recordAssistantCalls(listOf("search_contacts"))
        assertFalse(tracker.consume("get_contact"))
        assertTrue(tracker.consume("search_contacts"))
        assertFalse(tracker.consume("search_contacts"))
    }
    @Test fun `prose and conversation reset retire pending calls`() {
        val tracker = NativeToolReplyTracker()
        tracker.recordAssistantCalls(listOf("get_contact"))
        tracker.recordAssistantCalls(emptyList())
        assertFalse(tracker.consume("get_contact"))
        tracker.recordAssistantCalls(listOf("get_contact"))
        tracker.reset()
        assertFalse(tracker.consume("get_contact"))
    }
}
