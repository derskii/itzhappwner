package com.happwner.hooks

import java.lang.reflect.Constructor
import java.lang.reflect.Executable

object XposedBridge {
    @JvmStatic
    fun hookMethod(executable: Executable, callback: XC_MethodHook): XC_MethodHook.Unhook =
        XposedCompat.hook(executable, callback)

    @JvmStatic
    fun hookAllMethods(clazz: Class<*>, methodName: String, callback: XC_MethodHook): Set<XC_MethodHook.Unhook> =
        clazz.declaredMethods.asSequence()
            .filter { it.name == methodName }
            .map { XposedCompat.hook(it, callback) }
            .toSet()

    @JvmStatic
    fun hookAllConstructors(clazz: Class<*>, callback: XC_MethodHook): Set<XC_MethodHook.Unhook> =
        clazz.declaredConstructors.asSequence()
            .map { constructor: Constructor<*> -> XposedCompat.hook(constructor, callback) }
            .toSet()
}
