package com.happwner

import android.util.Log
import com.happwner.hooks.ModernHookBackend
import com.happwner.hooks.XposedCompat
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.util.concurrent.ConcurrentHashMap

/** Native libxposed API 102 module entry point. */
class ModernXposedModule : XposedModule() {
    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        installForPackage(param.packageName, param.classLoader)
    }

    private fun installForPackage(packageName: String, classLoader: ClassLoader) {
        XposedCompat.installBackend(ModernHookBackend(this))
        if (!processedPackages.add(packageName)) return
        try {
            MainHook().handlePackage(packageName, classLoader)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to install hooks for $packageName", t)
        }
    }

    companion object {
        private const val TAG = "Happwner:ModernXposed"
        private val processedPackages = ConcurrentHashMap.newKeySet<String>()
    }
}
