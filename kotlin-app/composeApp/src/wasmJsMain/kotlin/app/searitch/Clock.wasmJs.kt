package app.searitch

import kotlin.time.TimeSource

/**
 * Kotlin/Wasm has no epoch clock in the standard library, so the web build
 * counts monotonically from start-up. Feed ages therefore only mean something
 * within one page session — which is exactly how long the browser cache keeps
 * them anyway. A negative age after a reload is treated as "unknown".
 */
private val origin = TimeSource.Monotonic.markNow()

actual fun currentTimeMillis(): Long = origin.elapsedNow().inWholeMilliseconds
