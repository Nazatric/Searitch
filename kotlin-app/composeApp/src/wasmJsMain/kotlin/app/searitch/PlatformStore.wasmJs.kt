package app.searitch

import kotlinx.browser.localStorage

/**
 * Wasm store: everything lives in localStorage. The browser already caches the
 * images themselves, so only the feed JSON needs a home here.
 */
actual object PlatformStore {
    private const val FILE_PREFIX = "feed:"

    private fun ls() = runCatching { localStorage }.getOrNull()

    actual fun getString(key: String): String? =
        runCatching { ls()?.getItem(key) }.getOrNull()

    actual fun putString(key: String, value: String) {
        runCatching { ls()?.setItem(key, value) }
    }

    actual fun remove(key: String) {
        runCatching { ls()?.removeItem(key) }
    }

    actual suspend fun readFile(name: String): String? =
        runCatching { ls()?.getItem(FILE_PREFIX + name) }.getOrNull()

    actual suspend fun writeFile(name: String, text: String) {
        runCatching { ls()?.setItem(FILE_PREFIX + name, text) }
    }

    actual suspend fun listFiles(): List<String> = runCatching {
        val store = ls() ?: return emptyList()
        (0 until store.length)
            .mapNotNull { i -> store.key(i) }
            .filter { it.startsWith(FILE_PREFIX) }
            .map { it.removePrefix(FILE_PREFIX) }
    }.getOrDefault(emptyList())

    actual suspend fun clearFiles() {
        runCatching {
            val store = ls() ?: return
            listFiles().forEach { store.removeItem(FILE_PREFIX + it) }
        }
    }

    actual suspend fun filesSize(): Long = runCatching {
        listFiles().sumOf { (readFile(it)?.length ?: 0).toLong() * 2 }
    }.getOrDefault(0L)
}
