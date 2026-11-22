package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoenixPlayerSignerParityTest {
    @Test fun matchesOoiUaPlayerSignReferenceVector() {
        val query = "aid=8662&device_id=123456789"
        val body = """{"video_id":"123"}""".toByteArray(Charsets.UTF_8)
        val nonce = ByteArray(11) { it.toByte() }
        assertEquals(
            "8404401c0000f27a76e6957ca1b97022103fad22c9d970977ebb",
            PhoenixPlayerSigner.gorgon(query, body, 1700000000)
        )
        assertEquals(
            "AgMEBVbVr6bCGXEl3RbL1aAkpFHJpdxRfjTl94yWLoz6K/SQ",
            PhoenixPlayerSigner.playerLadon("8662", 1700000000, nonce.copyOfRange(2, 6))
        )
        assertEquals(
            "PMw3qMRaFflguIKYlC2mdpV2IWcBCwn1Koj5IoaLq5b3ZXfATS3iituUmnt2HJFxDCWIpwc/xgRjqtkv5n4uLADEdr1fboupPgZ/Kabe2EMEaQ3/o7oMc8hY3WR/n/piCdIOxKzo9jPh8pSH+K+pvGX42LJEU1ciK+i5QxX7TOyhmIHS+K9t673Uc5uPBA18ECoTk5nn361Gl9LORhAI2E5sgtZNeEdG0ZiGxg8C5aL1iFWeyfmPcEQPxdTh/1DX2LE5dW23fo7nBJN4BnwtNwMYQKz+Lx6nN1D5KGt2wQ9f+w==",
            PhoenixPlayerSigner.playerArgus(query, body, 1700000000, "123456789", nonce)
        )
    }
}
