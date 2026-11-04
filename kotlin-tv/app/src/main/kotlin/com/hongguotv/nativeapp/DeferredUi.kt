package com.hongguotv.nativeapp

internal data class DeferredUi(
    val apply: () -> Unit,
    val discard: () -> Unit = {}
)
