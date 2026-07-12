package com.example.smssender.model

data class AppConfig(
    val apiUrl: String = "",
    val apiKey: String = "",
    val smtpHost: String = "",
    val smtpPort: String = "465",
    val security: SmtpSecurity = SmtpSecurity.SSL_TLS,
    val username: String = "",
    val password: String = "",
    val from: String = "",
    val to: String = "",
)

enum class SmtpSecurity(val label: String) {
    SSL_TLS("SSL/TLS"),
    STARTTLS("STARTTLS"),
}
