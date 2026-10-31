package app.searitch

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Last few searches, so the app greets you with somewhere to go. */
object Recents {
    private const val KEY = "recent.searches.v1"
    private const val LIMIT = 8
    private val json = Json { ignoreUnknownKeys = true }

    fun list(): List<String> {
        val raw = runCatching { PlatformStore.getString(KEY) }.getOrNull() ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(String.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    fun add(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val next = (listOf(q) + list().filterNot { it.equals(q, ignoreCase = true) }).take(LIMIT)
        runCatching {
            PlatformStore.putString(KEY, json.encodeToString(ListSerializer(String.serializer()), next))
        }
    }

    fun clear() {
        runCatching { PlatformStore.remove(KEY) }
    }
}
