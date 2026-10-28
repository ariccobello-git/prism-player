package dev.red74.prismplayer.data

import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaylistStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private lateinit var file: File
    private var ticks = 0
    private var nextId = 0

    @Before
    fun setUp() {
        file = File(folder.root, "playlists.bin")
    }

    private fun newStore() = PlaylistStore(
        file = file,
        box = SecureBox { key },
        clock = { 1_000L + ticks++ },
        idGenerator = { "id-${nextId++}" },
    )

    @Test
    fun startsEmpty() = runBlocking {
        val store = newStore()
        store.load()
        assertTrue(store.items.value.isEmpty())
    }

    @Test
    fun savedPlaylistsSurviveARestart() = runBlocking {
        newStore().apply {
            load()
            add("One", "https://a.example.com/1.m3u")
            add("Two", "https://b.example.com/2.m3u")
        }

        val reopened = newStore()
        reopened.load()
        assertEquals(listOf("One", "Two"), reopened.items.value.map { it.name })
        assertEquals("https://b.example.com/2.m3u", reopened.items.value[1].url)
    }

    @Test
    fun theFileNeverContainsPlaintextUrls() = runBlocking {
        val store = newStore()
        store.load()
        store.add("Secret", "https://example.com/private/list.m3u?token=SUPERSECRET")

        val raw = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse(raw.contains("SUPERSECRET"))
        assertFalse(raw.contains("example.com"))
        assertFalse(raw.contains("Secret"))
    }

    @Test
    fun duplicateUrlsAreNotAddedTwice() = runBlocking {
        val store = newStore()
        store.load()
        val first = store.add("A", "https://example.com/a.m3u")
        val again = store.add("Renamed", "https://example.com/a.m3u")
        assertEquals(first.id, again.id)
        assertEquals(1, store.items.value.size)
    }

    @Test
    fun removeDeletesAndPersists() = runBlocking {
        val store = newStore()
        store.load()
        val a = store.add("A", "https://example.com/a.m3u")
        store.add("B", "https://example.com/b.m3u")
        store.remove(a.id)

        val reopened = newStore()
        reopened.load()
        assertEquals(listOf("B"), reopened.items.value.map { it.name })
    }

    @Test
    fun findReturnsTheSavedEntry() = runBlocking {
        val store = newStore()
        store.load()
        val a = store.add("A", "https://example.com/a.m3u")
        assertEquals(a, store.find(a.id))
        assertEquals(null, store.find("missing"))
    }

    @Test
    fun corruptFileIsSetAsideAndStoreRecovers() = runBlocking {
        file.writeBytes(ByteArray(64) { it.toByte() })

        val store = newStore()
        store.load()
        assertTrue(store.items.value.isEmpty())
        assertTrue(File(file.path + ".corrupt").exists())

        store.add("Fresh", "https://example.com/new.m3u")
        val reopened = newStore()
        reopened.load()
        assertEquals(listOf("Fresh"), reopened.items.value.map { it.name })
    }

    @Test
    fun aDifferentKeyCannotReadTheStore() = runBlocking {
        newStore().apply {
            load()
            add("A", "https://example.com/a.m3u")
        }

        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val intruder = PlaylistStore(file, SecureBox { otherKey })
        intruder.load()
        assertTrue(intruder.items.value.isEmpty())
    }
}
