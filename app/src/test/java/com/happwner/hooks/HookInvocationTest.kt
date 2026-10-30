package com.happwner.hooks

import io.github.libxposed.api.XposedInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Executable
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class HookInvocationTest {
    private val target = Target()
    private val method = Target::class.java.getDeclaredMethod("echo", String::class.java)

    @Test
    fun beforeHookCanSkipOriginalAndAfterCanReplaceResult() {
        var originalRan = false
        val callback = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.result = "before"
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                assertEquals("before", param.result)
                param.result = "after"
            }
        }

        val result = HookInvocation.invoke(callback, method, target, arrayOf("input")) {
            originalRan = true
            "original"
        }

        assertFalse(originalRan)
        assertEquals("after", result)
    }

    @Test
    fun editedArgumentsReachOriginalAndAfterHook() {
        val callback = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.args[0] = "edited"
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                param.result = "after:${param.result}"
            }
        }

        val result = HookInvocation.invoke(callback, method, target, arrayOf("original")) { args ->
            "seen:${args[0]}"
        }

        assertEquals("after:seen:edited", result)
    }

    @Test
    fun throwingBeforeHookRestoresControlStateAndContinuesOriginal() {
        var originalRan = false
        val callback = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.result = "partial"
                throw IllegalStateException("broken before")
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                assertEquals("original", param.result)
            }
        }

        val result = HookInvocation.invoke(callback, method, target, arrayOf("input")) {
            originalRan = true
            "original"
        }

        assertTrue(originalRan)
        assertEquals("original", result)
    }

    @Test
    fun throwingAfterHookRestoresOriginalOutcome() {
        val callback = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                param.result = "partial"
                throw IllegalStateException("broken after")
            }
        }

        val result = HookInvocation.invoke(callback, method, target, arrayOf("input")) { "original" }
        assertEquals("original", result)
    }

    @Test
    fun afterHookCanReplaceOrClearOriginalException() {
        val originalError = IllegalStateException("original")
        val replacement = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                assertSame(originalError, param.throwable)
                param.result = "recovered"
            }
        }
        assertEquals("recovered", HookInvocation.invoke(replacement, method, target, arrayOf("input")) {
            throw originalError
        })

        val clearing = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                param.throwable = null
            }
        }
        assertNull(HookInvocation.invoke(clearing, method, target, arrayOf("input")) {
            throw originalError
        })
    }

    @Test
    fun modernBackendMapsPriorityAndPreservesConstructorReceiverForAfterHook() {
        val constructor = Target::class.java.getDeclaredConstructor()
        val receiver = Target()
        var installedPriority: Int? = null
        var exceptionMode: XposedInterface.ExceptionMode? = null
        var installedHooker: XposedInterface.Hooker? = null
        val hookHandle = proxy(XposedInterface.HookHandle::class.java) { _, _, _ -> null }
        val builder = proxy(XposedInterface.HookBuilder::class.java) { self, called, args ->
            when (called.name) {
                "setPriority" -> {
                    installedPriority = args!![0] as Int
                    self
                }
                "setExceptionMode" -> {
                    exceptionMode = args!![0] as XposedInterface.ExceptionMode
                    self
                }
                "intercept" -> {
                    installedHooker = args!![0] as XposedInterface.Hooker
                    hookHandle
                }
                else -> null
            }
        }
        val xposed = proxy(XposedInterface::class.java) { _, called, _ ->
            if (called.name == "hook") builder else null
        }
        val callback = object : XC_MethodHook(173) {
            override fun afterHookedMethod(param: MethodHookParam) {
                assertSame(receiver, param.thisObject)
            }
        }

        val unhook = ModernHookBackend(xposed).hook(constructor, callback)
        assertSame(constructor, unhook.origin)
        assertEquals(173, installedPriority)
        assertEquals(XposedInterface.ExceptionMode.PASSTHROUGH, exceptionMode)

        val chain = proxy(XposedInterface.Chain::class.java) { _, called, _ ->
            when (called.name) {
                "getExecutable" -> constructor
                "getThisObject" -> receiver
                "getArgs" -> emptyList<Any>()
                "proceed" -> null
                else -> null
            }
        }
        assertNull(installedHooker!!.intercept(chain))
    }

    private fun <T> proxy(type: Class<T>, handler: InvocationHandler): T =
        type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type), handler))

    private class Target {
        @Suppress("unused")
        fun echo(value: String): String = value
    }
}
