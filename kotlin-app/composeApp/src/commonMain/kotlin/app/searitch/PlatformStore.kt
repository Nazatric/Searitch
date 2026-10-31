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
}
