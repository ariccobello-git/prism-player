package dev.red74.prismplayer.data

import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Persists saved playlists as one encrypted file (see [SecureBox]).
 * Plaintext URLs exist only in memory; the file never contains them in the clear.
 */
class PlaylistStore(
    private val file: File,
    private val box: SecureBox,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    @Serializable
    private data class StoreFile(val version: Int = 1, val playlists: List<SavedPlaylist> = emptyList())

    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }
    private val _items = MutableStateFlow<List<SavedPlaylist>>(emptyList())

    val items: StateFlow<List<SavedPlaylist>> = _items.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        synchronized(lock) { _items.value = readLocked() }
    }

    sealed interface AddResult {
        data class Added(val playlist: SavedPlaylist) : AddResult
        data class Duplicate(val existing: SavedPlaylist) : AddResult
    }

    /** True when a list with the same address is already saved. Safe to call from any thread. */
    fun contains(url: String): Boolean = _items.value.any { PlaylistUrl.sameList(it.url, url) }

    /** Adds a playlist unless the same list is already saved, which is reported instead of silently merged. */
    suspend fun addIfNew(name: String, url: String): AddResult = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val current = readLocked()
            current.firstOrNull { PlaylistUrl.sameList(it.url, url) }?.let { return@synchronized AddResult.Duplicate(it) }
            val created = SavedPlaylist(id = idGenerator(), name = name, url = url, addedAtEpochMs = clock())
            val updated = current + created
            writeLocked(updated)
            _items.value = updated
            AddResult.Added(created)
        }
    }

    /** Adds a playlist; an already-saved URL is returned as is instead of being duplicated. */
    suspend fun add(name: String, url: String): SavedPlaylist = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val current = readLocked()
            current.firstOrNull { PlaylistUrl.sameList(it.url, url) }?.let { return@synchronized it }
            val created = SavedPlaylist(
                id = idGenerator(),
                name = name,
                url = url,
                addedAtEpochMs = clock(),
            )
            val updated = current + created
            writeLocked(updated)
            _items.value = updated
            created
        }
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val current = readLocked()
            val updated = current.filterNot { it.id == id }
            if (updated.size != current.size) {
                writeLocked(updated)
            }
            _items.value = updated
        }
    }

    /** Records the outcome of a check. A missing playlist (deleted meanwhile) is ignored. */
    suspend fun updateStatus(
        id: String,
        status: PlaylistStatus,
        channelCount: Int,
        error: String = "",
        httpCode: Int = 0,
    ) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val current = readLocked()
            if (current.none { it.id == id }) return@synchronized
            val updated = current.map {
                if (it.id != id) it else it.copy(
                    status = status,
                    channelCount = if (status == PlaylistStatus.OK) channelCount else it.channelCount,
                    checkedAtEpochMs = clock(),
                    lastError = if (status == PlaylistStatus.FAILED) error else "",
                    lastHttpCode = if (status == PlaylistStatus.FAILED) httpCode else 0,
                )
            }
            writeLocked(updated)
            _items.value = updated
        }
    }

    fun find(id: String): SavedPlaylist? = _items.value.firstOrNull { it.id == id }

    private fun readLocked(): List<SavedPlaylist> {
        if (!file.exists()) return emptyList()
        return try {
            val plain = box.decrypt(file.readBytes())
            json.decodeFromString(StoreFile.serializer(), plain.decodeToString()).playlists
        } catch (e: Exception) {
            // Unreadable (corrupt file or lost key): keep it aside for inspection and start clean.
            runCatching { file.renameTo(File(file.path + ".corrupt")) }
            emptyList()
        }
    }

    private fun writeLocked(playlists: List<SavedPlaylist>) {
        val payload = json.encodeToString(StoreFile.serializer(), StoreFile(playlists = playlists))
        val sealed = box.encrypt(payload.encodeToByteArray())
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(sealed)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw java.io.IOException("could not write playlist store")
            }
        }
    }
}
