// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeviceRegisterTest {
    @Test fun buildsTHLAnonymousRegistrationRequestWithoutLegacyEncryptionOrIds() {
        val payload = JSONObject(String(DeviceRegister.payload("cdid-value", "openudid-value", 1234), Charsets.UTF_8))
        val header = payload.getJSONObject("header")

        assertEquals("ss_app_log", payload.getString("magic_tag"))
        assertEquals(1234L, payload.getLong("_gen_time"))
        assertEquals(8662, header.getInt("aid"))
        assertEquals("novelread", header.getString("app_name"))
        assertEquals("com.phoenix.read", header.getString("package"))
        assertEquals("7.3.9.32", header.getString("app_version"))
        assertEquals(73932, header.getInt("version_code"))
        assertEquals("6.18.0", header.getString("sdk_version"))
        assertEquals("16", header.getString("os_version"))
        assertEquals(36, header.getInt("os_api"))
        assertEquals("25053RT47C", header.getString("device_model"))
        assertEquals("Redmi", header.getString("device_brand"))
        assertEquals("Xiaomi", header.getString("device_manufacturer"))
        assertEquals("1280x2772", header.getString("resolution"))
        assertEquals("cdid-value", header.getString("cdid"))
        assertEquals("openudid-value", header.getString("openudid"))
        assertFalse(header.has("device_id"))
        assertFalse(header.has("install_id"))
        assertEquals(
            "https://log.snssdk.com/service/2/device_register/?aid=8662&app_name=novelread",
            DeviceRegister.url(),
        )
        assertEquals("application/json; charset=utf-8", DeviceRegister.headers()["Content-Type"])
        assertEquals(DeviceRegister.USER_AGENT, DeviceRegister.headers()["User-Agent"])
        assertFalse(DeviceRegister.headers().keys.any { it.startsWith("x-gorgon") || it.startsWith("x-argus") })
    }

    @Test fun parsesBothStringAndNumericRegistrationIdentityFields() {
        val parsed = DeviceRegister.parse(
            """{"device_id_str":"7345678901234567100","install_id_str":"7345678901234567200","device_token":"token","new_user":1}""",
            listOf("install_id=7345678901234567200; Path=/"),
        )!!
        assertEquals("7345678901234567100", parsed.deviceId)
        assertEquals("7345678901234567200", parsed.installId)
        assertEquals("token", parsed.deviceToken)
        assertTrue(parsed.newUser)
        assertEquals("7345678901234567200", parsed.cookies["install_id"])

        val numeric = DeviceRegister.parse(
            """{"device_id":7345678901234567,"install_id":7345678901234568}""",
        )!!
        assertEquals("7345678901234567", numeric.deviceId)
        assertEquals("7345678901234568", numeric.installId)
    }

    @Test fun rejectsMissingOrZeroRegistrationIdentity() {
        assertNull(DeviceRegister.parse("""{"device_id_str":"0","install_id_str":"7345678901234567200"}"""))
        assertNull(DeviceRegister.parse("""{"device_id_str":"7345678901234567100"}"""))
        assertNull(DeviceRegister.parse("not-json"))
    }
}
