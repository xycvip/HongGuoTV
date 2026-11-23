// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PlaybackDeviceSessionTest {
    private class MemoryStore(var value: String? = null) : PlaybackDeviceProfileStore {
        override fun load(): String? = value
        override fun save(value: String?) { this.value = value }
    }

    @Test fun persistsRegisteredIdentityAndRestoresItWithoutAnotherRegistration() {
        val store = MemoryStore()
        var registrations = 0
        val first = PlaybackDeviceSession {
            registrations++
            ThlDeviceRegister.Registered(
                deviceId = "123456789012",
                installId = "987654321098",
                cookies = mapOf("install_id" to "987654321098", "ttreq" to "region"),
            )
        }
        first.configureStore(store)
        val registered = first.profile(OkHttpClient())
        assertEquals(1, registrations)
        assertNotNull(store.value)

        val restored = PlaybackDeviceSession {
            error("persisted THL device must not register again")
        }
        restored.configureStore(store)
        val reused = restored.profile(OkHttpClient())
        assertEquals(registered.values, reused.values)
        assertEquals(registered.cookie, reused.cookie)
        assertEquals(1, registrations)
    }

    @Test fun discardsMalformedPersistedIdentity() {
        val store = MemoryStore("""{"cdid":"bad","openudid":"bad","device_id":"1","iid":"2"}""")
        var registrations = 0
        val session = PlaybackDeviceSession {
            registrations++
            ThlDeviceRegister.Registered("123456789012", "987654321098")
        }
        session.configureStore(store)
        val profile = session.profile(OkHttpClient())
        assertEquals(1, registrations)
        assertEquals("123456789012", profile.values["device_id"])
    }
}
