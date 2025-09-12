package com.ebani.sinage.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ebani.sinage.data.db.AppDatabase
import com.ebani.sinage.data.p.DevicePrefs
import com.ebani.sinage.data.repo.ContentRepository
import com.ebani.sinage.net.Net
import com.ebani.sinage.util.FileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs the config + content sync.
 */
class SyncWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = DevicePrefs(appContext)
        val deviceId = prefs.deviceId
        if (deviceId.isNullOrEmpty()) {
            return@withContext Result.failure()
        }

        val api = Net.api
        val db = AppDatabase.getInstance(appContext)
        val store = FileStore(appContext)
        val repo = ContentRepository(appContext, api, db, store, prefs)

        val res = repo.syncLayoutOnly()
        if (res.isSuccess) Result.success() else Result.retry()
    }
}
