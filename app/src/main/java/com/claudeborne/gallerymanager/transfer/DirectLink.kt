package com.claudeborne.gallerymanager.transfer

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import java.security.SecureRandom

// A direct Wi-Fi link between two phones, with no router and no internet: one phone makes a Wi-Fi Direct group (a small access point of
// its own, asked to use the 5 GHz band), the other joins it like any Wi-Fi network. The transfer then runs over it as it does over a router.

/** The network a phone made for the other phone to join, and the address of the phone on it. */
class DirectGroup(val ssid: String, val passphrase: String, val host: String)

/** The receiving side: makes the group. The caller must hold the NEARBY_WIFI_DEVICES permission. */
@SuppressLint("MissingPermission")
class DirectLink(context: Context) {
    private val appContext = context.applicationContext
    private val manager: WifiP2pManager? = context.getSystemService(WifiP2pManager::class.java)
    private val channel: WifiP2pManager.Channel? = manager?.initialize(context, Looper.getMainLooper(), null)
    private val handler = Handler(Looper.getMainLooper())

    /** Makes the group; [onReady] or [onError] runs on the main thread. 5 GHz is asked for first, and any band is accepted if that fails. */
    fun start(onReady: (DirectGroup) -> Unit, onError: (String) -> Unit) {
        val m = manager
        val c = channel
        if (m == null || c == null) return onError("This phone cannot make a direct link")
        val random = SecureRandom()
        val chars = "abcdefghjkmnpqrstuvwxyz23456789"
        fun word(n: Int) = (1..n).map { chars[random.nextInt(chars.length)] }.joinToString("")
        // the network name is made once and kept, so the other phone sees the same name each time (the password is new every time);
        // Wi-Fi Direct names must begin with DIRECT-xx
        val prefs = appContext.getSharedPreferences("direct", Context.MODE_PRIVATE)
        val ssid = prefs.getString("ssid", null) ?: ("DIRECT-gm-" + word(4)).also { prefs.edit().putString("ssid", it).apply() }
        val pass = word(10)

        fun create(band: Int) {
            val config = WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(pass).setGroupOperatingBand(band).build()
            m.createGroup(c, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = waitForGroup(m, c, 0, onReady, onError)
                override fun onFailure(reason: Int) {
                    if (band == WifiP2pConfig.GROUP_OWNER_BAND_5GHZ) create(WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
                    else onError("The direct link did not start (${if (reason == WifiP2pManager.BUSY) "busy" else if (reason == WifiP2pManager.P2P_UNSUPPORTED) "not supported" else "error $reason"}). Check that Wi-Fi is on.")
                }
            })
        }
        // a group left over from an earlier session would make createGroup fail as busy
        m.removeGroup(c, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = create(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
            override fun onFailure(reason: Int) = create(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
        })
    }

    private fun waitForGroup(m: WifiP2pManager, c: WifiP2pManager.Channel, tries: Int, onReady: (DirectGroup) -> Unit, onError: (String) -> Unit) {
        m.requestGroupInfo(c) { group ->
            val pass = group?.passphrase
            if (group != null && group.isGroupOwner && pass != null) {
                m.requestConnectionInfo(c) { info -> onReady(DirectGroup(group.networkName, pass, info.groupOwnerAddress?.hostAddress ?: "192.168.49.1")) }
            } else if (tries < 20) {
                handler.postDelayed({ waitForGroup(m, c, tries + 1, onReady, onError) }, 500) // the group takes a moment to come up
            } else {
                onError("The direct link did not start")
            }
        }
    }

    fun stop() {
        val c = channel ?: return
        manager?.removeGroup(c, null)
    }
}

/** The sending side: joins the group made by the other phone. */
object DirectJoin {
    /**
     * Joins the Wi-Fi network [ssid]. Android asks the user to confirm. [onNetwork] gets the network once connected (bind sockets to it),
     * [onFail] runs if the user declines or the network is not found. Returns a function that leaves the network again.
     */
    fun join(context: Context, ssid: String, passphrase: String, onNetwork: (Network) -> Unit, onFail: (String) -> Unit): () -> Unit {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // this link has no internet
            .setNetworkSpecifier(WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(passphrase).build())
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onNetwork(network)
            override fun onUnavailable() = onFail("Could not join the other phone's direct link")
        }
        cm.requestNetwork(request, callback, 60_000) // Android asks the user to pick the network, so there is time for that
        return { runCatching { cm.unregisterNetworkCallback(callback) } }
    }

    /** True if this phone already has an address on the same local network as [host] (so no joining is needed). */
    fun onSameNetwork(host: String): Boolean = runCatching {
        val target = java.net.InetAddress.getByName(host).address
        java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces()).any { ni ->
            ni.isUp && !ni.isLoopback && ni.interfaceAddresses.any { a ->
                val mine = a.address.address
                mine.size == target.size && mine.size == 4 && prefixMatches(mine, target, a.networkPrefixLength.toInt())
            }
        }
    }.getOrDefault(false)

    private fun prefixMatches(a: ByteArray, b: ByteArray, bits: Int): Boolean {
        for (i in 0 until bits) if (((a[i / 8].toInt() xor b[i / 8].toInt()) shr (7 - i % 8)) and 1 != 0) return false
        return true
    }
}
