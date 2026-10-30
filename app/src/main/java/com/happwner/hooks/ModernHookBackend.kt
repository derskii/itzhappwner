package com.happwner.hooks

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable

internal class ModernHookBackend(private val xposed: XposedInterface) : HookBackend {
    override fun hook(executable: Executable, callback: XC_MethodHook): XC_MethodHook.Unhook {
        val handle = xposed.hook(executable)
            .setPriority(callback.priority)
            // HookInvocation contains hook callback errors. PASSTHROUGH ensures an exception
            // from the original method, restored by an after hook, propagates exactly once.
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? = HookInvocation.invoke(
                    callback = callback,
                    method = chain.executable,
                    thisObject = chain.thisObject,
                    args = chain.args.toTypedArray(),
                    proceed = { changedArgs -> chain.proceed(changedArgs) },
                )
            })
        return XC_MethodHook.Unhook(executable) { handle.unhook() }
    }
}
