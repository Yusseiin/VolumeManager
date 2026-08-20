package moe.chensi.volume.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class AppPreferencesStore(private val dataStore: DataStore<Preferences>) {
    companion object {
        private val key = stringPreferencesKey("apps")

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Everything is stored as a single blob, so without this a volume slider drag would
         * re-encode and rewrite the whole store on every step. A burst of changes collapses into
         * one write instead.
         */
        private const val SAVE_DEBOUNCE = 300L
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    @Serializable
    private data class SerializedState(
        val values: MutableList<AppPreferences>,
        val indices: MutableMap<String, Int>,
        val systemSliderVisibility: MutableMap<String, Boolean> = mutableMapOf()
    )

    private val lock = Any()
    private var state = SerializedState(mutableListOf(), mutableMapOf())
    private var saveJob: Job? = null

    /**
     * Preferences and their indices, taken together so the two can't disagree: [getOrCreate] adds
     * to both, and reading them separately can catch it half way.
     */
    fun snapshot(): Pair<List<AppPreferences>, Map<String, Int>> = synchronized(lock) {
        state.values.toList() to state.indices.toMap()
    }

    fun getSystemSliderVisible(id: String): Boolean {
        return synchronized(lock) { state.systemSliderVisibility[id] ?: true }
    }

    fun setSystemSliderVisible(id: String, value: Boolean) {
        val changed = synchronized(lock) {
            val oldValue = state.systemSliderVisibility[id] ?: true
            if (oldValue == value) {
                return@synchronized false
            }

            val updated = state.systemSliderVisibility.toMutableMap()
            updated[id] = value
            state = state.copy(systemSliderVisibility = updated)
            true
        }

        if (changed) {
            save()
        }
    }

    var systemSliderVisibility: Map<String, Boolean>
        get() = synchronized(lock) { state.systemSliderVisibility.toMap() }
        set(value) {
            val changed = synchronized(lock) {
                if (state.systemSliderVisibility == value) {
                    return@synchronized false
                }

                state = state.copy(systemSliderVisibility = value.toMutableMap())
                true
            }

            if (changed) {
                save()
            }
        }

    fun track(onChange: (first: Boolean) -> Unit) {
        var first = true

        scope.launch {
            dataStore.data.collect { preferences ->
                val valueJson = preferences[key]
                if (valueJson != null) {
                    synchronized(lock) {
                        state = json.decodeFromString<SerializedState>(valueJson)
                    }
                }

                onChange(first)
                @Suppress("AssignedValueIsNeverRead")
                first = false
            }
        }
    }

    fun getOrCreate(packageName: String): AppPreferences {
        synchronized(lock) {
            val index = state.indices[packageName]
            if (index != null) {
                return state.values[index]
            }

            val value = AppPreferences()
            state.indices[packageName] = state.values.size
            state.values.add(value)
            return value
        }
    }

    fun save() {
        synchronized(lock) {
            saveJob?.cancel()
            saveJob = scope.launch {
                delay(SAVE_DEBOUNCE)

                // Deep copy while holding the lock: `App` keeps mutating these objects from the
                // main thread, and `getOrCreate` appends to the collections, while this encodes
                val snapshot = synchronized(lock) {
                    SerializedState(
                        state.values.map { it.copy() }.toMutableList(),
                        state.indices.toMutableMap(),
                        state.systemSliderVisibility.toMutableMap()
                    )
                }

                // Only the waiting is cancellable, a write that started has to finish
                withContext(NonCancellable) {
                    dataStore.edit { preferences ->
                        preferences[key] = json.encodeToString(snapshot)
                    }
                }
            }
        }
    }
}
