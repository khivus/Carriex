package com.example.carriex.transport

import okhttp3.OkHttpClient
import java.util.concurrent.Executors

private val cleanup = Executors.newSingleThreadExecutor { task ->
    Thread(task, "carriex-tls-cleanup").apply { isDaemon = true }
}

/** TLS close-notify can write to the network, even when evicting an idle connection. */
internal fun OkHttpClient.closeOffThread() {
    cleanup.execute {
        dispatcher.cancelAll()
        dispatcher.executorService.shutdown()
        connectionPool.evictAll()
    }
}
