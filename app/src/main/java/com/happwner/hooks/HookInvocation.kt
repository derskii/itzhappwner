package com.happwner.hooks

import android.util.Log

internal object HookInvocation {
    private const val TAG = "Happwner:HookCompat"

    fun invoke(
        callback: XC_MethodHook,
        method: java.lang.reflect.Executable,
        thisObject: Any?,
        args: Array<Any?>,
        proceed: (Array<Any?>) -> Any?,
    ): Any? {
        val param = XC_MethodHook.MethodHookParam(method, thisObject, args)
        invokeBefore(callback, param)

        if (!param.shouldSkipOriginal) {
            try {
                param.setOriginalResult(proceed(param.args))
            } catch (t: Throwable) {
                param.setOriginalThrowable(t)
            }
        }

        invokeAfter(callback, param)
        param.throwable?.let { throw it }
        return param.result
    }

    fun invokeBefore(callback: XC_MethodHook, param: XC_MethodHook.MethodHookParam) {
        val state = param.snapshot()
        try {
            callback.beforeHookedMethod(param)
        } catch (t: Throwable) {
            param.restore(state)
            logFailure("before", param, t)
        }
    }

    fun invokeAfter(callback: XC_MethodHook, param: XC_MethodHook.MethodHookParam) {
        val state = param.snapshot()
        try {
            callback.afterHookedMethod(param)
        } catch (t: Throwable) {
            param.restore(state)
            logFailure("after", param, t)
        }
    }

    private fun logFailure(phase: String, param: XC_MethodHook.MethodHookParam, throwable: Throwable) {
        try {
            Log.e(TAG, "$phase hook failed for ${param.method}", throwable)
        } catch (_: Throwable) {
            // Android's Log implementation is stubbed in local JVM unit tests.
        }
    }
}
