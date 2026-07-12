package com.example.smssender.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmailContentBuilderTest {
    @Test
    fun `验证码放入标题且号码只出现在正文`() {
        val result = EmailContentBuilder.build(
            sender = "+86 13800138000",
            originalBody = "【示例】您的验证码是 482913，5 分钟内有效。",
        )

        assertEquals("验证码：482913", result.subject)
        assertEquals(
            "来源号码：+86 13800138000\n\n【示例】您的验证码是 482913，5 分钟内有效。",
            result.body,
        )
    }

    @Test
    fun `支持验证码出现在英文关键词之前`() {
        assertEquals(
            "A7B9K2",
            EmailContentBuilder.findVerificationCode("A7B9K2 is your verification code. Do not share it."),
        )
    }

    @Test
    fun `无验证码关键词时不把普通数字当验证码`() {
        val result = EmailContentBuilder.build(
            sender = "10086",
            originalBody = "您本月已使用流量 123456 MB。",
        )

        assertEquals("短信转发", result.subject)
        assertNull(EmailContentBuilder.findVerificationCode("订单 123456 已发货"))
    }
}
