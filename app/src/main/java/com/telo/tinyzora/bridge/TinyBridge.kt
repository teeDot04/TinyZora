package com.telo.tinyzora.bridge

import android.content.Context
import android.content.Intent
import android.util.Log

class TinyBridge(private val context: Context) {

    companion object {
        private const val PERMISSION_SIGNATURE = "com.telo.orlix.permission.TINY_ACCESS"
        private const val PKG_ORLIX = "com.telo.orlix.admin"
        private const val PKG_TRADEFLOW = "com.telo.tradeflow.app"
        private const val ACTION_ORLIX_CMD = "com.telo.orlix.ACTION_EXECUTE"
        private const val ACTION_TRADEFLOW_CMD = "com.telo.tradeflow.ACTION_LOG"
    }

    fun routeExternalCommand(domain: String, commandJson: String) {
        when (domain) {
            "ORLIX", "BUSINESS" -> sendBroadcast(PKG_ORLIX, ACTION_ORLIX_CMD, commandJson)
            "TRADEFLOW", "TRADING" -> sendBroadcast(PKG_TRADEFLOW, ACTION_TRADEFLOW_CMD, commandJson)
            else -> Log.w("TinyBridge", "Domain $domain is not external.")
        }
    }

    private fun sendBroadcast(targetPackage: String, action: String, json: String) {
        val intent = Intent(action).apply {
            `package` = targetPackage
            putExtra("PAYLOAD", json)
            putExtra("TIMESTAMP", System.currentTimeMillis())
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }
        context.sendBroadcast(intent, PERMISSION_SIGNATURE)
        Log.i("TinyBridge", "Sent to $targetPackage: $json")
    }
}
