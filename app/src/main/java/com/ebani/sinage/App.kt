package com.ebani.sinage

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.ebani.sinage.data.p.DevicePrefs
import com.ebani.sinage.net.Net
import com.ebani.sinage.net.SocketHub
import com.ebani.sinage.net.SocketHub.stopheartBeat
import com.ebani.sinage.net.ws.MsgUnpairReset
import com.ebani.sinage.net.ws.PairingMessage
import com.ebani.sinage.util.PlayerBus
import timber.log.Timber

class App : Application() {
    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        Timber.plant(Timber.DebugTree())



        SocketHub.start(appContext, Net.WS_URL)
        SocketHub.addListener(
            object : SocketHub.Listener {
                @RequiresApi(Build.VERSION_CODES.R)
                override fun onConnected() {
                    SocketHub.emitHandshake(DevicePrefs(appContext).deviceId)
                    PlayerBus.commands.tryEmit(PlayerBus.Command.EmitRegisteredShake)
                }

                override fun onDisconnected() {
                    stopheartBeat()
                }

                @RequiresApi(Build.VERSION_CODES.R)
                override fun onReconnect() {
                    PlayerBus.commands.tryEmit(PlayerBus.Command.EmitRegisteredShake)
                }

                override fun onMessage(msg: PairingMessage) = handleSocketMessage(msg)
            }
        )





        // quick reachability test
//        val url = "http://172.26.224.1:3000/api/socket" // or http://10.0.2.2:3000/api/socket when local
//        Thread {
//            try {
//                val body = okhttp3.OkHttpClient.Builder()
//                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
//                    .build()
//                    .newCall(
//                        okhttp3.Request.Builder().url(url).get().build()
//                    ).execute().use { it.body?.string() }
//                println("HTTP test OK: $body")
//            } catch (e: Exception) {
//                e.printStackTrace()
//            }
//        }.start()
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

    fun handleSocketMessage(msg: PairingMessage) {
        when (msg) {
            is MsgUnpairReset -> {
                PlayerBus.commands.tryEmit(PlayerBus.Command.CheckWithServerForPair)
                stopheartBeat()
//                println("WS msg: $msg")

            }

            else -> {}
        }

    }



    companion object {
        lateinit var appContext: Context
            private set
    }
}
