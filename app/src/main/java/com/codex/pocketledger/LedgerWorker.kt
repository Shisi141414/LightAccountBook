package com.codex.pocketledger

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class LedgerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val repo = LedgerRepository(applicationContext)
        if (repo.folderUri != null) repo.syncOnce()
        repo.processAutomation()
        if (repo.folderUri != null) repo.syncOnce()
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }
}

fun scheduleLedgerWorker(context: Context) {
    val request = PeriodicWorkRequestBuilder<LedgerWorker>(1, TimeUnit.DAYS).build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "ledger-daily", ExistingPeriodicWorkPolicy.KEEP, request)
}
