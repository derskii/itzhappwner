package com.happwner.hooks

import java.lang.reflect.Executable

internal interface HookBackend {
    fun hook(executable: Executable, callback: XC_MethodHook): XC_MethodHook.Unhook
}

/** Process-local backend installed by the matching legacy or libxposed entry point. */
object XposedCompat {
    @Volatile
    private var backend: HookBackend? = null

    internal fun installBackend(value: HookBackend) {
        backend = value
    }

    internal fun hook(executable: Executable, callback: XC_MethodHook): XC_MethodHook.Unhook =
        checkNotNull(backend) { "No Xposed hook backend is active in this process" }
            .hook(executable, callback)
}
