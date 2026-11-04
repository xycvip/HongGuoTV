// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context

/** 主 Activity 与独立第三方片源页面之间的生命周期和页面重建桥接。 */
internal fun MainActivity.openThirdSource() {
    val enabled=getSharedPreferences("appearance-settings-v1",Context.MODE_PRIVATE)
        .getBoolean("show_third_source",false)
    if(!enabled) {
        showCatalog()
        return
    }
    screen="third_source"
    val container=base()
    val instance=thirdSourceScreen ?: ThirdSourceScreen(
        activity=this,
        rebuild={
            if(screen=="third_source" && !isFinishing && !isDestroyed) {
                val host=base()
                thirdSourceScreen?.show(host)
            }
        }
    ).also { thirdSourceScreen=it }
    instance.show(container)
}
