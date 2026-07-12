package com.example.smssender.network

import com.example.smssender.data.PendingMessage
import com.example.smssender.model.AppConfig
import com.example.smssender.model.SmtpSecurity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ApiResult(
    val successful: Boolean,
    val retryable: Boolean,
    val message: String,
)

object ForwardApi {
    fun send(config: AppConfig, message: PendingMessage): ApiResult {
        val connection = (URL(config.apiUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 25_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }

        return try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use {
                it.write(requestBody(config, message).toString())
            }
            val status = connection.responseCode
            val response = runCatching {
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }.getOrDefault("")
            val detail = runCatching {
                val json = JSONObject(response)
                json.optJSONObject("error")?.optString("message")
                    ?: json.optString("message")
            }
                .getOrDefault("")
                .ifBlank { "HTTP $status" }
            when {
                status in 200..299 -> ApiResult(true, false, "转发成功")
                status == 408 || status == 429 || status >= 500 -> ApiResult(false, true, detail)
                else -> ApiResult(false, false, detail)
            }
        } catch (error: Exception) {
            ApiResult(false, true, error.message ?: "网络连接失败")
        } finally {
            connection.disconnect()
        }
    }

    private fun requestBody(config: AppConfig, message: PendingMessage): JSONObject {
        val email = EmailContentBuilder.build(message.sender, message.body)
        return JSONObject()
            .put(
                "smtp",
                JSONObject()
                    .put("host", config.smtpHost)
                    .put("port", config.smtpPort.toInt())
                    .put("secure", config.security == SmtpSecurity.SSL_TLS)
                    .put("user", config.username)
                    .put("pass", config.password),
            )
            .put(
                "mail",
                JSONObject()
                    .put("from", config.from)
                    .put("to", config.to)
                    .put("subject", email.subject)
                    .put("text", email.body),
            )
    }
}
