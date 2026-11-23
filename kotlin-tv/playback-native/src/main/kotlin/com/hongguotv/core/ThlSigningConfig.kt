// THLRedGuo N-line signing profile.
// These values were explicitly supplied by the project owner.
// NOTE: This file only holds the profile inputs; the THL v04.10.03
// profile packet/signature algorithm must consume them before the
// N-line can be considered fully aligned.
package com.hongguotv.core

import java.util.Base64

internal object ThlSigningConfig {
    private const val ID_BASE64 = "NjQ2NjM3Nzk0"
    private const val MATERIAL_BASE64 = "sjwqRwog0QXcP4xEOPgnLtRtbIn/3N7dUQhpjSbpSJ0="

    val id: ByteArray by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Base64.getDecoder().decode(ID_BASE64)
    }

    val material: ByteArray by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Base64.getDecoder().decode(MATERIAL_BASE64).also {
            require(it.size == 32) { "Invalid THL signing material length" }
        }
    }
}
