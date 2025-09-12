package com.ebani.sinage

import android.app.Application
import android.content.Context
import androidx.work.*
import com.ebani.sinage.net.Net
import com.ebani.sinage.net.SocketHub
//import com.ebani.sinage.data.prefs.DevicePrefs
import com.ebani.sinage.sync.SyncWorker
import java.util.concurrent.TimeUnit
import timber.log.Timber

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        Timber.plant(Timber.DebugTree())
        SocketHub.start(this, Net.WS_URL)
        // Schedule periodic sync (runs even when offline; retries when online)
//        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
//            .setConstraints(
//                Constraints.Builder()
//                    .setRequiredNetworkType(NetworkType.CONNECTED) // can be NOT_REQUIRED if you want it to run offline too
//                    .build()
//            ).build()
//        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
//            "content-sync",
//            ExistingPeriodicWorkPolicy.KEEP,
//            req
//        )
    }



    companion object {
        lateinit var appContext: Context
            private set
    }
}
