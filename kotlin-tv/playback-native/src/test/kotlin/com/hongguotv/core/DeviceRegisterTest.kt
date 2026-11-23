// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ThlDeviceRegisterTest {
    @Test fun buildsTHLAnonymousRegistrationRequestWithoutLegacyEncryptionOrIds() {
        val payload = JSONObject(String(ThlDeviceRegister.payload("cdid-value", "openudid-value", 1234), Charsets.UTF_8))
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
            ThlDeviceRegister.url(),
        )
        assertEquals("application/json; charset=utf-8", ThlDeviceRegister.headers()["Content-Type"])
        assertEquals(ThlDeviceRegister.USER_AGENT, ThlDeviceRegister.headers()["User-Agent"])
        assertFalse(ThlDeviceRegister.headers().keys.any { it.startsWith("x-gorgon") || it.startsWith("x-argus") })
    }

    @Test fun parsesBothStringAndNumericRegistrationIdentityFields() {
        val parsed = ThlDeviceRegister.parse(
            """{"device_id_str":"7345678901234567100","install_id_str":"7345678901234567200","device_token":"token","new_user":1}""",
            listOf("install_id=7345678901234567200; Path=/"),
        )!!
        assertEquals("7345678901234567100", parsed.deviceId)
        assertEquals("7345678901234567200", parsed.installId)
        assertEquals("token", parsed.deviceToken)
        assertTrue(parsed.newUser)
        assertEquals("7345678901234567200", parsed.cookies["install_id"])

        val numeric = ThlDeviceRegister.parse(
            """{"device_id":7345678901234567,"install_id":7345678901234568}""",
        )!!
        assertEquals("7345678901234567", numeric.deviceId)
        assertEquals("7345678901234568", numeric.installId)
    }

    @Test fun sessionUsesRegisteredIdentityAndTHLProfileForNRequests() {
        var captured: okhttp3.Request? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            captured = chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body("""{"device_id_str":"7345678901234567100","install_id_str":"7345678901234567200"}""".toResponseBody())
                .build()
        }.build()
        val profile = PlaybackDeviceSession().profile(client)
        val request = captured!!
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        val body = JSONObject(buffer.readUtf8())
        val header = body.getJSONObject("header")

        assertEquals(ThlDeviceRegister.url(), request.url.toString())
        assertEquals(ThlDeviceRegister.USER_AGENT, request.header("User-Agent"))
        assertEquals("application/json; charset=utf-8", request.header("Content-Type"))
        assertEquals("com.phoenix.read", header.getString("package"))
        assertEquals(profile.values["cdid"], header.getString("cdid"))
        assertEquals(profile.values["openudid"], header.getString("openudid"))
        assertEquals("7345678901234567100", profile.values["device_id"])
        assertEquals("7345678901234567200", profile.values["iid"])
        assertEquals("25053RT47C", profile.values["device_type"])
        assertEquals("73932", profile.values["version_code"])
        assertTrue(profile.cookie.isEmpty())
    }

    @Test fun rejectsMissingOrZeroRegistrationIdentity() {
        assertNull(ThlDeviceRegister.parse("""{"device_id_str":"0","install_id_str":"7345678901234567200"}"""))
        assertNull(ThlDeviceRegister.parse("""{"device_id_str":"7345678901234567100"}"""))
        assertNull(ThlDeviceRegister.parse("not-json"))
    }
}
