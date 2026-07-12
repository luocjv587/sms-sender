package com.example.smssender.validation

import com.example.smssender.model.AppConfig
import java.net.URI

object ConfigValidator {
    private val email = Regex("^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$", RegexOption.IGNORE_CASE)
    private val host = Regex("^(?=.{1,253}$)([A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?\\.)*[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?$", RegexOption.IGNORE_CASE)

    fun validate(config: AppConfig): Map<String, String> = buildMap {
        val uri = runCatching { URI(config.apiUrl.trim()) }.getOrNull()
        if (uri == null || uri.scheme != "https" || uri.host.isNullOrBlank()) {
            put("apiUrl", "请输入有效的 HTTPS API 地址")
        }
        if (config.apiKey.isBlank()) put("apiKey", "API Key 不能为空")
        if (!host.matches(config.smtpHost.trim())) put("smtpHost", "SMTP 主机格式不正确")
        val port = config.smtpPort.toIntOrNull()
        if (port == null || port !in 1..65535 || port == 25) {
            put("smtpPort", "端口应为 1–65535，且不能使用 25")
        }
        if (config.username.isBlank()) put("username", "用户名不能为空")
        if (config.password.isBlank()) put("password", "密码或授权码不能为空")
        if (!email.matches(config.from.trim())) put("from", "发件邮箱格式不正确")
        if (!email.matches(config.to.trim())) put("to", "收件邮箱格式不正确")
    }
}
