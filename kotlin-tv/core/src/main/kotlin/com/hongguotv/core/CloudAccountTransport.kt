// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** 账号 API 的签名、请求体编码与 HTTP 响应处理。 */
internal class CloudAccountTransport(private val http: OkHttpClient) {
    fun registerDevice(): DeviceRegister.Registered? {
        val body = DeviceRegister.payload(VendorConstants.device)
        val request = Request.Builder().url(DeviceRegister.url())
        DeviceRegister.headers(body, VendorConstants.device).forEach { (k, v) ->
            request.header(k, v)
        }
        request.post(body.toRequestBody("application/octet-stream".toMediaType()))
        return http.newCall(request.build()).execute().use {
            DeviceRegister.parse(cloudResponse(it), it.headers("Set-Cookie"))
        }
    }

    fun json(
        credentials: AccountCredentials,
        path: String,
        params: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        gzip: Boolean = false,
    ): JSONObject {
        val device =
            VendorConstants.device.toMutableMap().apply {
                putAll(credentials.device)
                put("device_id", credentials.deviceId)
                put("iid", credentials.installId)
                for (key in
                    listOf(
                        "version_code",
                        "manifest_version_code",
                        "update_version_code",
                        "pv_player",
                    )) put(key, if (body != null) "73932" else "72232")
                put("version_name", if (body != null) "7.3.9.32" else "7.2.2.32")
            }
        val wireBody =
            if (gzip && body != null)
                java.io
                    .ByteArrayOutputStream()
                    .apply { java.util.zip.GZIPOutputStream(this).use { it.write(body) } }
                    .toByteArray()
            else body
        val signed =
            Signer(device = device)
                .sign("https://api5-normal-sinfonlinea.fqnovel.com$path", device + params, wireBody)
        val request = Request.Builder().url(signed.url).header("Cookie", credentials.cookie)
        signed.headers.forEach { (k, v) -> request.header(k, v) }
        if (wireBody != null)
            request.post(wireBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (gzip) request.header("Content-Encoding", "gzip")
        val transport =
            if (path == "/reading/bookapi/bookshelf/video/update/v1/")
                http.newBuilder().retryOnConnectionFailure(false).build()
            else http
        return transport.newCall(request.build()).execute().use { response ->
            val json = JSONObject(cloudResponse(response))
            val code = json.optInt("code", -1)
            if (code != 0) throw IOException("账号接口不可用（错误码 $code），请重新登录或稍后重试")
            json
        }
    }


}
