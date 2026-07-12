package com.example.smssender.validation

import com.example.smssender.model.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigValidatorTest {
    private val valid = AppConfig(
        apiUrl = "https://example.vercel.app/api/forward",
        apiKey = "secret",
        smtpHost = "smtp.example.com",
        smtpPort = "465",
        username = "sender@example.com",
        password = "app-password",
        from = "sender@example.com",
        to = "receiver@example.com",
    )

    @Test
    fun `valid configuration has no errors`() {
        assertTrue(ConfigValidator.validate(valid).isEmpty())
    }

    @Test
    fun `API must use HTTPS`() {
        assertEquals(
            "请输入有效的 HTTPS API 地址",
            ConfigValidator.validate(valid.copy(apiUrl = "http://example.com"))["apiUrl"],
        )
    }

    @Test
    fun `SMTP port 25 is rejected`() {
        assertTrue(ConfigValidator.validate(valid.copy(smtpPort = "25")).containsKey("smtpPort"))
    }

    @Test
    fun `invalid email addresses are rejected`() {
        val errors = ConfigValidator.validate(valid.copy(from = "bad", to = "also-bad"))
        assertTrue(errors.containsKey("from"))
        assertTrue(errors.containsKey("to"))
    }
}
