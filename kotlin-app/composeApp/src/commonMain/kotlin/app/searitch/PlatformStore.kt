package app.searitch

/**
 * Tiny persistent key/value + file store.
 *
 * Android  → SharedPreferences + filesDir (both are the platform's own
 *            cheap, crash-safe stores; no extra dependency).
 * Wasm     → localStorage.
 *
 * The store only ever holds text, so the UI can cache serialized feeds without
 * pulling in a database dependency.
 */
/**
 * The build's version, in one place.
 *
 * The settings sheet used to print a hand-written string that had drifted four
 * releases behind the APK, which is worse than showing nothing.
 */
object BuildInfo {
    const val VERSION = "2.1.0"
    const val VERSION_CODE = 22
}

expect object PlatformStore {
    /** Small values (settings, recent searches). */
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)

    /** Larger values (serialized feeds), kept out of the settings blob. */
    suspend fun readFile(name: String): String?
    suspend fun writeFile(name: String, text: String)
    suspend fun listFiles(): List<String>
    suspend fun clearFiles(): Unit
    suspend fun filesSize(): Long

    /** Bytes of cached feeds alone — the image cache is measured separately. */
    suspend fun feedsSize(): Long

    /** Bytes held by the decoded-image disk cache alone. */
    suspend fun imageCacheSize(): Long

    /** Drops decoded images; feeds and settings stay. */
    suspend fun clearImageCache(): Unit
}
