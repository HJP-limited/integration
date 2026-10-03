package com.example.hjp.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class KieContactFieldsTest {
    @Test fun urlMisclassifiedAsEmailIsPreservedAsWebsite() {
        for (url in listOf("www.example.com", "https://example.com/path?q=1",
            "http://example.co.kr", "https://example.com/contact?email=test@example.com")) {
            assertEquals("website" to url, KieContactFields.normalize("email", url))
        }
    }

    @Test fun wwwPrefixIsNeverStrippedAsASingleLetterLabel() {
        assertEquals("website" to "www.example.com", KieContactFields.normalize("website", "www.example.com"))
        for (label in listOf("W.", "Web:", "Website ", "Homepage:")) {
            assertEquals("website" to "www.example.com",
                KieContactFields.normalize("website", "$label www.example.com"))
        }
    }

    @Test fun emailMisclassifiedAsWebsiteKeepsItsActualAddress() {
        assertEquals("email" to "test@example.com",
            KieContactFields.normalize("website", "E. test@example.com"))
        assertEquals("email" to "www.name@example.com",
            KieContactFields.normalize("website", "www.name@example.com"))
    }

    @Test fun otherKieLabelsAndAmbiguousValuesAreNotReclassified() {
        assertEquals("company_en" to "www.example.com",
            KieContactFields.normalize("company_en", "www.example.com"))
        assertEquals("email" to "ambiguous value", KieContactFields.normalize("email", "ambiguous value"))
    }
}
