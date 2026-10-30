package com.happwner.hooks

import de.robv.android.xposed.XC_MethodHook as LegacyXCMethodHook
import de.robv.android.xposed.XposedBridge as LegacyXposedBridge
import java.lang.reflect.Executable

/** Adapter kept in a separate class so the API102 entry never links or invokes legacy Xposed APIs. */
internal class LegacyHookBackend : HookBackend {
    override fun hook(executable: Executable, callback: XC_MethodHook): XC_MethodHook.Unhook {
        val invocationStack = ThreadLocal.withInitial { ArrayDeque<XC_MethodHook.MethodHookParam>() }
        val legacyCallback = object : LegacyXCMethodHook(callback.priority) {
            override fun beforeHookedMethod(param: LegacyXCMethodHook.MethodHookParam) {
                val compat = XC_MethodHook.MethodHookParam(executable, param.thisObject, param.args)
                invocationStack.get().addLast(compat)
                HookInvocation.invokeBefore(callback, compat)
                if (!compat.shouldSkipOriginal) return
                compat.throwable?.let { param.throwable = it } ?: run { param.result = compat.result }
            }

            override fun afterHookedMethod(param: LegacyXCMethodHook.MethodHookParam) {
                val compat = invocationStack.get().removeLastOrNull()
                    ?: XC_MethodHook.MethodHookParam(executable, param.thisObject, param.args)
                if (param.hasThrowable()) compat.setOriginalThrowable(param.throwable)
                else compat.setOriginalResult(param.result)
                HookInvocation.invokeAfter(callback, compat)
                compat.throwable?.let { param.throwable = it } ?: run { param.result = compat.result }
            }
        }
        val legacyUnhook = LegacyXposedBridge.hookMethod(executable, legacyCallback)
        return XC_MethodHook.Unhook(executable) { legacyUnhook.unhook() }
    }
}
