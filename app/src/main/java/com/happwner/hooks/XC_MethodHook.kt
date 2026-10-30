package com.happwner.hooks

import java.lang.reflect.Executable
import java.util.concurrent.atomic.AtomicBoolean

open class XC_MethodHook @JvmOverloads constructor(val priority: Int = PRIORITY_DEFAULT) {
    open fun beforeHookedMethod(param: MethodHookParam) = Unit

    open fun afterHookedMethod(param: MethodHookParam) = Unit

    class MethodHookParam internal constructor(
        @JvmField val method: Executable,
        @JvmField val thisObject: Any?,
        @JvmField val args: Array<Any?>,
    ) {
        private var resultValue: Any? = null
        private var throwableValue: Throwable? = null
        private var skipOriginal = false

        var result: Any?
            get() = resultValue
            set(value) {
                resultValue = value
                throwableValue = null
                skipOriginal = true
            }

        var throwable: Throwable?
            get() = throwableValue
            set(value) {
                throwableValue = value
                if (value != null) {
                    resultValue = null
                    skipOriginal = true
                }
            }

        internal val shouldSkipOriginal: Boolean
            get() = skipOriginal

        internal data class State(val result: Any?, val throwable: Throwable?, val skipOriginal: Boolean)

        internal fun snapshot(): State = State(resultValue, throwableValue, skipOriginal)

        internal fun restore(state: State) {
            resultValue = state.result
            throwableValue = state.throwable
            skipOriginal = state.skipOriginal
        }

        internal fun setOriginalResult(value: Any?) {
            resultValue = value
            throwableValue = null
        }

        internal fun setOriginalThrowable(value: Throwable) {
            resultValue = null
            throwableValue = value
        }
    }

    class Unhook internal constructor(
        val origin: Executable,
        private val action: () -> Unit,
    ) {
        private val removed = AtomicBoolean(false)

        fun unhook() {
            if (removed.compareAndSet(false, true)) action()
        }
    }

    companion object {
        const val PRIORITY_DEFAULT = 50
        const val PRIORITY_LOWEST = Int.MIN_VALUE
        const val PRIORITY_HIGHEST = Int.MAX_VALUE
    }
}

abstract class XC_MethodReplacement @JvmOverloads constructor(priority: Int = PRIORITY_DEFAULT) : XC_MethodHook(priority) {
    final override fun beforeHookedMethod(param: MethodHookParam) {
        try {
            param.result = replaceHookedMethod(param)
        } catch (t: Throwable) {
            param.throwable = t
        }
    }

    protected abstract fun replaceHookedMethod(param: MethodHookParam): Any?

    companion object {
        @JvmStatic
        fun returnConstant(value: Any?): XC_MethodReplacement = object : XC_MethodReplacement() {
            override fun replaceHookedMethod(param: MethodHookParam): Any? = value
        }
    }
}
