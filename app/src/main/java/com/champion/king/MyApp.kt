package com.champion.king  // ← 改成你的實際 namespace

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.Log
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.appcheck.ktx.appCheck
import com.google.firebase.ktx.Firebase

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Release 版本使用 Play Integrity
        Log.d("AppCheck", "Using PlayIntegrityAppCheckProvider")
        val factory = PlayIntegrityAppCheckProviderFactory.getInstance()
        Firebase.appCheck.installAppCheckProviderFactory(factory)
    }
}
