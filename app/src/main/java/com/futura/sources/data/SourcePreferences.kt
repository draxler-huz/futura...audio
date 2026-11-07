package com.futura.sources.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.futura.sources.SourceConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.sourceDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "source_preferences"
)

/**
 * Persists the user's per-source on/off choice. The registry holds the
 * defaults; this store holds only the differences, so adding a new source
 * to the registry does not require a migration.
 */
@Singleton
class SourcePreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** A map of sourceId → enabled, for every source the user has changed. */
    val overrides: Flow<Map<String, Boolean>> = context.sourceDataStore.data
        .catch { e ->
            // A read error must not break search; fall back to no overrides.
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { prefs ->
            prefs.asMap()
                .filterKeys { it.name.startsWith(KEY_PREFIX) }
                .mapNotNull { (key, value) ->
                    val id = key.name.removePrefix(KEY_PREFIX)
                    (value as? Boolean)?.let { id to it }
                }
                .toMap()
        }

    suspend fun setEnabled(sourceId: String, enabled: Boolean) {
        context.sourceDataStore.edit { prefs ->
            prefs[booleanPreferencesKey(KEY_PREFIX + sourceId)] = enabled
        }
    }

    suspend fun apply(configs: List<SourceConfig>, enabled: Boolean) {
        context.sourceDataStore.edit { prefs ->
            configs.forEach { prefs[booleanPreferencesKey(KEY_PREFIX + it.id)] = enabled }
        }
    }

    private companion object {
        const val KEY_PREFIX = "source_enabled_"
    }
}
