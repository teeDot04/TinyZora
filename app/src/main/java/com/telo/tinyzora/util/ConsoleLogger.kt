package com.telo.tinyzora.util
import android.util.Log
object ConsoleLogger {
    fun d(tag: String, msg: String) { Log.d(tag, msg) }
    fun e(tag: String, msg: String, e: Exception? = null) { Log.e(tag, msg, e) }
}
