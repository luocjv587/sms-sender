package com.example.smssender.sms

import android.content.Intent
import android.provider.Telephony

data class SmsPart(
    val sender: String,
    val body: String,
    val receivedAt: Long,
    val order: Int,
)

data class ParsedSms(
    val sender: String,
    val body: String,
    val receivedAt: Long,
)

object SmsParser {
    fun fromIntent(intent: Intent): List<ParsedSms> {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return emptyList()
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent).mapIndexed { index, message ->
            SmsPart(
                sender = message.displayOriginatingAddress.orEmpty().ifBlank { "未知号码" },
                body = message.displayMessageBody.orEmpty(),
                receivedAt = message.timestampMillis,
                order = index,
            )
        }
        return merge(parts)
    }

    /**
     * Android delivers every PDU of one concatenated SMS in the same ordered broadcast.
     * Grouping by sender keeps unrelated PDUs separate while preserving the platform PDU order.
     */
    fun merge(parts: List<SmsPart>): List<ParsedSms> =
        parts.groupBy { it.sender }.map { (sender, senderParts) ->
            ParsedSms(
                sender = sender,
                body = senderParts.sortedBy { it.order }.joinToString(separator = "") { it.body },
                receivedAt = senderParts.minOf { it.receivedAt },
            )
        }
}
