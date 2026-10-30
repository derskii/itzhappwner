package com.happwner.hooks

import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier

object XposedHelpers {
    @JvmStatic
    fun findAndHookMethod(
        className: String,
        classLoader: ClassLoader?,
        methodName: String,
        vararg parameterTypesAndCallback: Any,
    ): XC_MethodHook.Unhook {
        val clazz = findClass(className, classLoader)
        return findAndHookMethod(clazz, methodName, *parameterTypesAndCallback)
    }

    @JvmStatic
    fun findAndHookMethod(
        clazz: Class<*>,
        methodName: String,
        vararg parameterTypesAndCallback: Any,
    ): XC_MethodHook.Unhook {
        val (parameterTypes, callback) = parseHookArguments(clazz.classLoader, parameterTypesAndCallback)
        val method = findMethod(clazz, methodName, parameterTypes)
        return XposedCompat.hook(method, callback)
    }

    @JvmStatic
    fun findAndHookConstructor(
        className: String,
        classLoader: ClassLoader?,
        vararg parameterTypesAndCallback: Any,
    ): XC_MethodHook.Unhook {
        val clazz = findClass(className, classLoader)
        return findAndHookConstructor(clazz, *parameterTypesAndCallback)
    }

    @JvmStatic
    fun findAndHookConstructor(
        clazz: Class<*>,
        vararg parameterTypesAndCallback: Any,
    ): XC_MethodHook.Unhook {
        val (parameterTypes, callback) = parseHookArguments(clazz.classLoader, parameterTypesAndCallback)
        val constructor = try {
            clazz.getDeclaredConstructor(*parameterTypes).apply { isAccessible = true }
        } catch (t: Throwable) {
            throw NoSuchMethodError("${clazz.name}(${parameterTypes.joinToString { it.name }})").apply { initCause(t) }
        }
        return XposedCompat.hook(constructor, callback)
    }

    @JvmStatic
    fun findClass(className: String, classLoader: ClassLoader?): Class<*> =
        Class.forName(className, false, classLoader)

    @JvmStatic
    fun findClassIfExists(className: String, classLoader: ClassLoader?): Class<*>? =
        try {
            findClass(className, classLoader)
        } catch (_: Throwable) {
            null
        }

    @JvmStatic
    fun callMethod(receiver: Any?, methodName: String, vararg args: Any?): Any? {
        val target = requireNotNull(receiver) { "Cannot call $methodName on a null receiver" }
        val method = findBestMethod(target.javaClass, methodName, args)
        method.isAccessible = true
        return method.invoke(target, *args)
    }

    @JvmStatic
    fun callStaticMethod(clazz: Class<*>, methodName: String, vararg args: Any?): Any? {
        val method = findBestMethod(clazz, methodName, args)
        require(Modifier.isStatic(method.modifiers)) { "${method.declaringClass.name}.$methodName is not static" }
        method.isAccessible = true
        return method.invoke(null, *args)
    }

    private fun parseHookArguments(
        classLoader: ClassLoader?,
        parameterTypesAndCallback: Array<out Any>,
    ): Pair<Array<Class<*>>, XC_MethodHook> {
        require(parameterTypesAndCallback.isNotEmpty()) { "A hook callback is required" }
        val callback = parameterTypesAndCallback.last() as? XC_MethodHook
            ?: throw IllegalArgumentException("The last hook argument must be an XC_MethodHook")
        val parameterTypes = parameterTypesAndCallback.dropLast(1).map { type ->
            when (type) {
                is Class<*> -> type
                is String -> findClass(type, classLoader)
                else -> throw IllegalArgumentException("Unsupported parameter type: $type")
            }
        }.toTypedArray()
        return parameterTypes to callback
    }

    private fun findMethod(clazz: Class<*>, methodName: String, parameterTypes: Array<Class<*>>): Method {
        var current: Class<*>? = clazz
        while (current != null) {
            try {
                return current.getDeclaredMethod(methodName, *parameterTypes).apply { isAccessible = true }
            } catch (_: NoSuchMethodException) {
                current = current.superclass
            }
        }
        throw NoSuchMethodError("${clazz.name}.$methodName(${parameterTypes.joinToString { it.name }})")
    }

    private fun findBestMethod(clazz: Class<*>, methodName: String, args: Array<out Any?>): Method {
        var current: Class<*>? = clazz
        while (current != null) {
            val candidate = current.declaredMethods.firstOrNull { method ->
                method.name == methodName && parametersMatch(method.parameterTypes, args)
            }
            if (candidate != null) return candidate
            current = current.superclass
        }
        throw NoSuchMethodError("${clazz.name}.$methodName/${args.size}")
    }

    private fun parametersMatch(parameterTypes: Array<Class<*>>, args: Array<out Any?>): Boolean =
        parameterTypes.size == args.size && parameterTypes.indices.all { index ->
            val arg = args[index]
            val type = parameterTypes[index].boxed()
            arg == null || type.isAssignableFrom(arg.javaClass)
        }

    private fun Class<*>.boxed(): Class<*> = when (this) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        else -> this
    }
}
