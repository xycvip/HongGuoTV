package com.hongguotv.quickjs

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.define

/**
 * Minimal bridge contract for an application-owned JavaScript host object.
 *
 * Method implementations stay in the Android app; this module only exposes
 * the stable host object expected by JavaScript API modules.
 */
interface QuickJsHostAdapter {
    val source: String
    fun invoke(name: String, args: Array<Any?>): Any?
}

private val hostFunctionNames = listOf(
    "aesCbcDecrypt",
    "aesCbcEncrypt",
    "base64Decode",
    "base64Encode",
    "cacheGet",
    "cacheSet",
    "catalogContext",
    "chapterId",
    "dramaId",
    "exclusive",
    "fetch",
    "fetchText",
    "finishMedia",
    "hexDecode",
    "hexEncode",
    "isKnown",
    "maxPages",
    "md5",
    "parseHtml",
    "parseUrl",
    "preserveIntegers",
    "randomBytes",
    "reportProgress",
    "reserve",
    "retries",
    "sha256",
    "sha512",
    "sleep",
    "utf8Decode",
    "utf8Encode",
)

/** Install the complete host API surface used by RedGuoAPI's Hongguo module. */
fun QuickJs.installHostAdapter(adapter: QuickJsHostAdapter) {
    define("host") {
        property("source") {
            getter { adapter.source }
        }
        hostFunctionNames.forEach { methodName ->
            function(methodName) { args ->
                adapter.invoke(methodName, args)
            }
        }
    }
}
