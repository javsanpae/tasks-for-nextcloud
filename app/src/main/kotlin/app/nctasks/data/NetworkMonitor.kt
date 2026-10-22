// Copyright (C) 2026 Javier Sánchez
// SPDX-License-Identifier: GPL-3.0-or-later
package app.nctasks.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow

/** Emits each time the default network becomes available (used to flush queued changes). */
fun networkAvailableFlow(context: Context): Flow<Unit> {
    val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return emptyFlow()
    return callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(Unit)
            }
        }
        val registered = runCatching { manager.registerDefaultNetworkCallback(callback) }.isSuccess
        awaitClose { if (registered) runCatching { manager.unregisterNetworkCallback(callback) } }
    }
}
