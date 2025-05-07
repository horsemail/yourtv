package com.horsenma.yourtv

import android.util.Log
import com.horsenma.yourtv.BuildConfig

object Logger {
    fun d(tag: String, message: String) {
        if (BuildConfig.ENABLE_LOG) {
            Log.d(tag, message)
        }
    }

    fun i(tag: String, message: String) {
        if (BuildConfig.ENABLE_LOG) {
            Log.i(tag, message)
        }
    }

    fun w(tag: String, message: String) {
        if (BuildConfig.ENABLE_LOG) {
            Log.w(tag, message)
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
    }
}