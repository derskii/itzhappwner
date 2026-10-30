package com.happwner

import com.happwner.hooks.LegacyHookBackend
import com.happwner.hooks.XposedCompat
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

/** API82 entry point. The hook implementation itself is shared with the modern backend. */
class LegacyModuleEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedCompat.installBackend(LegacyHookBackend())
        MainHook().handlePackage(lpparam.packageName, lpparam.classLoader)
    }
}
