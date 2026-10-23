package com.example.mypain2

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log

class MyVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("MyVpnService", "VPN service started")

        val builder = Builder()
        builder.setSession("My AdBlocker VPN")
        builder.addAddress("10.0.0.2", 24)
        builder.addRoute("0.0.0.0", 0)
        builder.addDnsServer("8.8.8.8")

        vpnInterface = builder.establish()

        if (vpnInterface == null) {
            Log.e("MyVpnService", "Failed to establish VPN")
        } else {
            Log.d("MyVpnService", "VPN established successfully")
        }

        return START_STICKY
    }
    override fun onDestroy() {
        super.onDestroy()
        vpnInterface?.close()
        Log.d("MyVpnService", "VPN service destroyed")
    }
}
