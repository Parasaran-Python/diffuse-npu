package com.example.sdnpu.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "sdnpu_settings")

/**
 * Repository managing user preferences via Jetpack DataStore Preferences.
 * Exposes a reactive [settingsFlow] and suspend update functions for all settings fields.
 */
class SettingsRepository(
    private val dataStore: DataStore<Preferences>
) {
    constructor(context: Context) : this(context.applicationContext.settingsDataStore)

    val settingsFlow: Flow<AppSettings> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            AppSettings(
                defaultSteps = preferences[KEY_STEPS] ?: 20,
                defaultCfgScale = preferences[KEY_CFG_SCALE] ?: 7.0f,
                defaultSampler = preferences[KEY_SAMPLER] ?: 0,
                defaultBatchCount = preferences[KEY_BATCH_COUNT] ?: 1,
                defaultUpscaleMode = preferences[KEY_UPSCALE_MODE] ?: 0,
                backendPreference = preferences[KEY_BACKEND] ?: AppSettings.DEFAULT_BACKEND,
                thermalWarningEnabled = preferences[KEY_THERMAL_WARNING] ?: true,
                highPerformanceMode = preferences[KEY_HIGH_PERFORMANCE] ?: false,
                darkThemeMode = preferences[KEY_DARK_THEME] ?: AppSettings.DEFAULT_DARK_THEME
            ).sanitized()
        }

    suspend fun getSettings(): AppSettings = settingsFlow.first()

    suspend fun updateSteps(steps: Int) {
        val sanitized = steps.coerceIn(10, 50)
        dataStore.edit { preferences ->
            preferences[KEY_STEPS] = sanitized
        }
    }

    suspend fun updateCfgScale(cfg: Float) {
        val sanitized = cfg.coerceIn(1.0f, 20.0f)
        dataStore.edit { preferences ->
            preferences[KEY_CFG_SCALE] = sanitized
        }
    }

    suspend fun updateSampler(sampler: Int) {
        val sanitized = sampler.coerceIn(0, 3)
        dataStore.edit { preferences ->
            preferences[KEY_SAMPLER] = sanitized
        }
    }

    suspend fun updateBatchCount(batchCount: Int) {
        val sanitized = batchCount.coerceIn(1, 4)
        dataStore.edit { preferences ->
            preferences[KEY_BATCH_COUNT] = sanitized
        }
    }

    suspend fun updateUpscaleMode(mode: Int) {
        val sanitized = mode.coerceIn(0, 2)
        dataStore.edit { preferences ->
            preferences[KEY_UPSCALE_MODE] = sanitized
        }
    }

    suspend fun updateBackend(backend: String) {
        val sanitized = if (backend in AppSettings.VALID_BACKENDS) backend else AppSettings.DEFAULT_BACKEND
        dataStore.edit { preferences ->
            preferences[KEY_BACKEND] = sanitized
        }
    }

    suspend fun updateThermalWarning(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_THERMAL_WARNING] = enabled
        }
    }

    suspend fun updateHighPerformanceMode(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_HIGH_PERFORMANCE] = enabled
        }
    }

    suspend fun updateDarkTheme(theme: String) {
        val sanitized = if (theme in AppSettings.VALID_THEMES) theme else AppSettings.DEFAULT_DARK_THEME
        dataStore.edit { preferences ->
            preferences[KEY_DARK_THEME] = sanitized
        }
    }

    suspend fun resetToDefaults() {
        dataStore.edit { preferences ->
            preferences.clear()
        }
    }

    companion object {
        val KEY_STEPS = intPreferencesKey("default_steps")
        val KEY_CFG_SCALE = floatPreferencesKey("default_cfg_scale")
        val KEY_SAMPLER = intPreferencesKey("default_sampler")
        val KEY_BATCH_COUNT = intPreferencesKey("default_batch_count")
        val KEY_UPSCALE_MODE = intPreferencesKey("default_upscale_mode")
        val KEY_BACKEND = stringPreferencesKey("backend_preference")
        val KEY_THERMAL_WARNING = booleanPreferencesKey("thermal_warning_enabled")
        val KEY_HIGH_PERFORMANCE = booleanPreferencesKey("high_performance_mode")
        val KEY_DARK_THEME = stringPreferencesKey("dark_theme_mode")

        @Volatile
        private var INSTANCE: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
