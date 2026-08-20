package moe.chensi.volume.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AppPreferencesStoreTest {
    private companion object {
        /** Writes are debounced, so give them room without making the test sleep blindly. */
        const val WRITE_TIMEOUT = 10_000L
    }

    private val key = stringPreferencesKey("apps")

    private lateinit var directory: File
    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: AppPreferencesStore

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("app-preferences").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(directory, "test.preferences_pb")
        }
        store = AppPreferencesStore(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
        directory.deleteRecursively()
    }

    /** Wait for what was written to satisfy [predicate], instead of guessing at a delay. */
    private fun awaitStored(predicate: (String) -> Boolean): String = runBlocking {
        withTimeout(WRITE_TIMEOUT) {
            dataStore.data.first { preferences -> preferences[key]?.let(predicate) == true }[key]!!
        }
    }

    @Test
    fun `getOrCreate returns the same preferences for a package`() {
        assertSame(store.getOrCreate("com.example.one"), store.getOrCreate("com.example.one"))
    }

    @Test
    fun `apps left at their defaults are not written`() {
        store.getOrCreate("com.example.untouched")
        store.getOrCreate("com.example.changed").volume = 0.5f
        store.save()

        val stored = awaitStored { it.contains("com.example.changed") }
        assertFalse(stored.contains("com.example.untouched"))
    }

    @Test
    fun `saving an app the store no longer knows puts it back`() {
        val preferences = store.getOrCreate("com.example.gone")
        preferences.volume = 0.25f
        // Stands in for the entry having been pruned as unmodified and reloaded without it
        store.remove("com.example.gone")

        store.save("com.example.gone", preferences)

        val stored = awaitStored { it.contains("com.example.gone") }
        assertTrue(stored, stored.contains("0.25"))
    }

    @Test
    fun `remove leaves the other apps pointing at their own preferences`() {
        val first = store.getOrCreate("com.example.first").apply { volume = 0.1f }
        store.getOrCreate("com.example.second").apply { volume = 0.2f }
        val third = store.getOrCreate("com.example.third").apply { volume = 0.3f }

        store.remove("com.example.second")

        assertSame(first, store.getOrCreate("com.example.first"))
        assertSame(third, store.getOrCreate("com.example.third"))
        assertEquals(0.3f, store.getOrCreate("com.example.third").volume, 0f)
    }

    @Test
    fun `snapshot pairs every package with its own preferences`() {
        store.getOrCreate("com.example.a").apply { volume = 0.1f }
        store.getOrCreate("com.example.b").apply { volume = 0.9f }

        val (values, indices) = store.snapshot()

        assertEquals(0.1f, values[indices.getValue("com.example.a")].volume, 0f)
        assertEquals(0.9f, values[indices.getValue("com.example.b")].volume, 0f)
    }

    @Test
    fun `slider visibility defaults to visible and is written when changed`() {
        assertTrue(store.getSystemSliderVisible("media"))

        store.setSystemSliderVisible("media", false)

        assertFalse(store.getSystemSliderVisible("media"))
        val stored = awaitStored { it.contains("systemSliderVisibility") }
        assertTrue(stored, stored.contains("\"media\":false"))
    }
}
