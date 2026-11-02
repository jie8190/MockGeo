package com.mockgeo.core.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SettingsRepositoryTest {
    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    @Test fun `fresh preferences do not opt into AMap SDK data processing`() = runTest {
        val repository = SettingsRepository(MemoryStore())
        assertThat(repository.settings.first().amapConsent).isFalse()
    }

    @Test fun `su path survives repository recreation and can restore default`() = runTest {
        val store = MemoryStore()
        val repository = SettingsRepository(store)
        assertThat(repository.settings.first().rootSuPath).isEmpty()
        repository.setRootSuPath(" /data/local/tmp/superuser ")
        assertThat(SettingsRepository(store).settings.first().rootSuPath).isEqualTo("/data/local/tmp/superuser")
        repository.setRootSuPath("")
        assertThat(SettingsRepository(store).settings.first().rootSuPath).isEmpty()
    }

    @Test fun `backup excludes executable and imported settings cannot replace local path`() = runTest {
        val repository = SettingsRepository(MemoryStore())
        repository.setRootSuPath("/data/local/tmp/device-su")
        val exported = repository.exportJson(repository.settings.first())
        assertThat(exported).doesNotContain("device-su")
        val imported = org.json.JSONObject(exported)
        imported.getJSONObject("settings").put("rootSuPath", "/another/su")
        repository.importJson(imported.toString())
        assertThat(repository.settings.first().rootSuPath).isEqualTo("/data/local/tmp/device-su")
    }

    @Test fun `choosing map or editing settings does not grant consent and withdrawal persists`() = runTest {
        val store = MemoryStore()
        val repository = SettingsRepository(store)
        repository.setMapProvider("amap")
        repository.setAccuracy(5.0)
        assertThat(repository.settings.first().amapConsent).isFalse()
        repository.setAmapConsent(true)
        assertThat(SettingsRepository(store).settings.first().amapConsent).isTrue()
        repository.setAmapConsent(false)
        val reopened = SettingsRepository(store).settings.first()
        assertThat(reopened.amapConsent).isFalse()
        assertThat(reopened.mapProvider).isEqualTo("osm")
    }
}
