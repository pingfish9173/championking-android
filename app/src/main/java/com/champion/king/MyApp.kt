package com.champion.king  // ← 改成你的實際 namespace

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.Log
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.appcheck.ktx.appCheck
import com.google.firebase.ktx.Firebase

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // 以執行時 flag 判斷是否為 debuggable（等同 debug 變體）
        val isDebug = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        val factory = if (isDebug) {
            Log.d("AppCheck", "Using DebugAppCheckProvider (runtime debuggable)")
            DebugAppCheckProviderFactory.getInstance()
        } else {
            Log.d("AppCheck", "Using PlayIntegrityAppCheckProvider (runtime debuggable=false)")
            PlayIntegrityAppCheckProviderFactory.getInstance()
        }

        Firebase.appCheck.installAppCheckProviderFactory(factory)
    }
}
