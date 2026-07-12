package com.example.smssender.network

data class EmailContent(
    val subject: String,
    val body: String,
)

object EmailContentBuilder {
    private val verificationKeyword = Regex(
        pattern = "验证码|校验码|动态码|短信码|一次性密码|OTP|verification\\s*code|security\\s*code|auth(?:entication)?\\s*code|passcode",
        option = RegexOption.IGNORE_CASE,
    )
    private val codeCandidate = Regex(
        pattern = "(?<![A-Za-z0-9])(?=[A-Za-z0-9]{4,8}(?![A-Za-z0-9]))(?=[A-Za-z0-9]*\\d)[A-Za-z0-9]+",
    )

    fun build(sender: String, originalBody: String): EmailContent {
        val code = findVerificationCode(originalBody)
        return EmailContent(
            subject = code?.let { "验证码：$it" } ?: "短信转发",
            body = "来源号码：$sender\n\n$originalBody",
        )
    }

    fun findVerificationCode(content: String): String? {
        val keyword = verificationKeyword.find(content) ?: return null
        return codeCandidate.findAll(content)
            .minByOrNull { candidate -> distance(candidate.range, keyword.range) }
            ?.value
    }

    private fun distance(first: IntRange, second: IntRange): Int = when {
        first.last < second.first -> second.first - first.last
        second.last < first.first -> first.first - second.last
        else -> 0
    }
}
