package com.happwner

import java.io.IOException
import java.io.Reader

/** Reads an array or sequence of JSON objects, retaining only one configuration at a time. */
object JsonSubscriptionStream {
    const val MAX_NESTING = 128

    fun forEachObject(reader: Reader, checkActive: () -> Unit = {}, consume: (String) -> Unit) {
        val input = reader.buffered()
        fun nextNonWhitespace(): Int {
            var ch: Int
            do { ch = input.read() } while (ch >= 0 && (ch.toChar().isWhitespace() || ch == 0xFEFF))
            return ch
        }
        var next = nextNonWhitespace()
        val array = next == '['.code
        if (array) next = nextNonWhitespace()
        if (array && next == ']'.code) {
            if (nextNonWhitespace() != -1) throw IOException("Trailing data")
            return
        }
        while (next != -1) {
            checkActive()
            if (next != '{'.code) throw IOException("Expected a JSON configuration")
            val objectText = StringBuilder("{")
            val stack = ArrayDeque<Char>().apply { addLast('}') }
            var quoted = false
            var escaped = false
            while (stack.isNotEmpty()) {
                val code = input.read()
                if (code < 0) throw IOException("Incomplete JSON configuration")
                val ch = code.toChar()
                objectText.append(ch)
                if (objectText.length % 8192 == 0) checkActive()
                if (objectText.length > SubscriptionFiles.MAX_PROCESS_BYTES / 2) {
                    throw IOException("Individual JSON configuration exceeds the processing limit")
                }
                if (quoted) {
                    if (escaped) escaped = false
                    else if (ch == '\\') escaped = true
                    else if (ch == '"') quoted = false
                } else when (ch) {
                    '"' -> quoted = true
                    '{', '[' -> {
                        if (stack.size >= MAX_NESTING) throw IOException("JSON nesting limit exceeded")
                        stack.addLast(if (ch == '{') '}' else ']')
                    }
                    '}', ']' -> if (stack.removeLast() != ch) throw IOException("Mismatched JSON brackets")
                }
            }
            consume(objectText.toString())
            next = nextNonWhitespace()
            if (array) {
                if (next == ']'.code) {
                    if (nextNonWhitespace() != -1) throw IOException("Trailing data")
                    return
                }
                if (next != ','.code) throw IOException("Expected array separator")
                next = nextNonWhitespace()
                if (next != '{'.code) throw IOException("Expected next JSON configuration")
            }
        }
        if (array) throw IOException("Unterminated JSON array")
    }

    /** Guard recursive org.json parsers before feeding them untrusted input. */
    fun checkNesting(text: String) {
        var depth = 0
        var quoted = false
        var escaped = false
        for (ch in text) {
            if (quoted) {
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') quoted = false
            } else when (ch) {
                '"' -> quoted = true
                '{', '[' -> { if (++depth > MAX_NESTING) throw IOException("JSON nesting limit exceeded") }
                '}', ']' -> depth--
            }
        }
    }
}
