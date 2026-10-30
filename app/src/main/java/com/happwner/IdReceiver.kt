package com.happwner

/** Format validation shared by the provider and UI for captured Android IDs. */
object IdReceiver {
    fun isValidCapturedId(value: String): Boolean =
        value.matches(Regex("(?i)([0-9a-f]{16}|[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12})"))
}
