package com.example.smssender.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.smssender.NotificationHelper
import com.example.smssender.data.AppDatabase
import com.example.smssender.data.PendingMessage
import com.example.smssender.data.StatusStore
import com.example.smssender.network.ForwardApi
import com.example.smssender.security.SecureConfigStore
import com.example.smssender.validation.ConfigValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class ForwardWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val config = SecureConfigStore(applicationContext).load()
        val errors = ConfigValidator.validate(config)
        if (errors.isNotEmpty()) {
            report(false, "配置不完整，请先检查设置")
            return@withContext Result.failure()
        }

        if (inputData.getBoolean(KEY_TEST, false)) {
            val testMessage = PendingMessage(
                sender = "配置测试",
                body = "短信转发器配置测试成功。",
                receivedAt = System.currentTimeMillis(),
            )
            val result = ForwardApi.send(config, testMessage)
            report(result.successful, result.message)
            return@withContext when {
                result.successful -> Result.success()
                result.retryable -> Result.retry()
                else -> Result.failure()
            }
        }

        val dao = AppDatabase.get(applicationContext).pendingMessageDao()
        while (true) {
            val pending = dao.next() ?: break
            val result = ForwardApi.send(config, pending)
            if (result.successful) {
                dao.delete(pending)
                report(true, "已转发来自 ${pending.sender} 的短信")
                continue
            }
            dao.recordFailure(pending.id, result.message)
            report(false, result.message)
            return@withContext if (result.retryable) Result.retry() else Result.failure()
        }
        Result.success()
    }

    private fun report(successful: Boolean, message: String) {
        StatusStore(applicationContext).update(successful, message)
        NotificationHelper.show(applicationContext, successful, message)
    }

    companion object {
        private const val KEY_TEST = "test"
        private const val UNIQUE_FORWARD = "forward-pending-sms"
        private const val UNIQUE_TEST = "test-forward-config"

        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ForwardWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_FORWARD,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }

        fun test(context: Context) {
            val request = OneTimeWorkRequestBuilder<ForwardWorker>()
                .setInputData(workDataOf(KEY_TEST to true))
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_TEST,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
