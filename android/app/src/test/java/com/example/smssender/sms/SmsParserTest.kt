package com.example.smssender.sms

import org.junit.Assert.assertEquals
import org.junit.Test

class SmsParserTest {
    @Test
    fun `multipart PDUs are merged in platform order`() {
        val parts = listOf(
            SmsPart("+8613800000000", "第二段", 1_002, 1),
            SmsPart("+8613800000000", "第一段", 1_000, 0),
            SmsPart("+8613800000000", "第三段", 1_004, 2),
        )

        assertEquals(
            ParsedSms("+8613800000000", "第一段第二段第三段", 1_000),
            SmsParser.merge(parts).single(),
        )
    }

    @Test
    fun `parts from different senders stay separate`() {
        val result = SmsParser.merge(
            listOf(
                SmsPart("10086", "A", 100, 0),
                SmsPart("95555", "B", 101, 1),
            ),
        )

        assertEquals(2, result.size)
        assertEquals(setOf("10086", "95555"), result.map { it.sender }.toSet())
    }
}
