package com.hongguotv.nativeapp.js

/**
 * Android capabilities exposed to remote JavaScript modules.
 *
 * Implementations must validate URLs against the active module manifest's host allow-list.
 * Return JS-compatible values (null, Boolean, Number, String, List, or Map).
 */
fun interface JsHostBridge {
    fun invoke(method: String, args: List<Any?>): Any?
}

/** The host surface used by RedGuoAPI/modules/hongguo/main.js. */
object HongGuoHostMethods {
    val names: Set<String> = setOf(
        "aesCbcDecrypt", "aesCbcEncrypt",
        "base64Decode", "base64Encode",
        "cacheGet", "cacheSet",
        "catalogContext", "chapterId", "dramaId",
        "exclusive", "fetch", "fetchText", "finishMedia",
        "hexDecode", "hexEncode", "isKnown",
        "maxPages", "md5", "parseHtml", "parseUrl",
        "preserveIntegers", "randomBytes", "reportProgress",
        "reserve", "retries", "sha256", "sha512", "sleep",
        "source", "utf8Decode", "utf8Encode"
    )
}
