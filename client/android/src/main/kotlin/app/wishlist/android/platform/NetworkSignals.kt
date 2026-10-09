package app.wishlist.android.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Network restore signal (C3: the platform only signals, the shared coordinator decides).
 * [onRestored] fires when the default network becomes INTERNET + VALIDATED after it was not
 * (lost, never connected, or not yet validated). The baseline is read at [start], so starting
 * online does not count as a restore. Callbacks arrive on the ConnectivityManager thread;
 * [onRestored] must be thread-safe (`requestFlush` is).
 */
class NetworkSignals(context: Context, private val onRestored: () -> Unit) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    @Volatile
    private var validated = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val now = capabilities.isOnline()
            val restored = now && !validated
            validated = now
            if (restored) onRestored()
        }

        override fun onLost(network: Network) {
            validated = false
        }
    }

    /** Registers once for the process lifetime (the Application owns it). */
    fun start() {
        validated = isOnline(connectivity)
        connectivity.registerDefaultNetworkCallback(callback)
    }

    companion object {
        /** The active network has INTERNET and VALIDATED (the share card's online/offline choice). */
        fun isOnline(context: Context): Boolean =
            isOnline(context.applicationContext.getSystemService(ConnectivityManager::class.java))

        private fun isOnline(connectivity: ConnectivityManager): Boolean =
            connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.isOnline() == true

        private fun NetworkCapabilities.isOnline() =
            hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
