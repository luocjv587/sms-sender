package com.example.smssender.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.example.smssender.data.AppDatabase
import com.example.smssender.data.PendingMessage
import com.example.smssender.worker.ForwardWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = SmsParser.fromIntent(intent)
        if (messages.isEmpty()) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.get(context).pendingMessageDao()
                messages.forEach { sms ->
                    dao.insert(
                        PendingMessage(
                            sender = sms.sender,
                            body = sms.body,
                            receivedAt = sms.receivedAt,
                        ),
                    )
                }
                ForwardWorker.enqueue(context)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
