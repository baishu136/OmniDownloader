package com.omni.downloader.ui.components

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.data.repository.SettingsRepository
import com.omni.downloader.engine.NetworkProxy
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RelayExpansionMemoryTest {
    @Test fun expandedAndCollapsedChoicesRestoreWithoutChangingOtherPreferences() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "relay-expansion-qa-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val scoped = object : ContextWrapper(context) {
            override fun getSharedPreferences(ignored: String?, mode: Int) = prefs
        }
        val previousProxy = NetworkProxy.configuredUrl
        fun repository(): SettingsRepository = SettingsRepository::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(scoped)
        try {
            prefs.edit().putString("key_download_path", "QA/Pictures").commit()
            val first = repository()
            val state = RelaySitesState(first.homeRelayExpanded.value, first::setHomeRelayExpanded)
            assertFalse(state.isExpanded)
            state.toggleExpanded()
            val restored = repository()
            assertTrue(restored.homeRelayExpanded.value)
            assertEquals("QA/Pictures", restored.downloadPath.value)
            RelaySitesState(restored.homeRelayExpanded.value, restored::setHomeRelayExpanded).toggleExpanded()
            assertFalse(repository().homeRelayExpanded.value)
            assertEquals("QA/Pictures", prefs.getString("key_download_path", ""))
        } finally { context.deleteSharedPreferences(name); NetworkProxy.configuredUrl = previousProxy }
    }
}
