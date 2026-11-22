package com.hongguotv.nativeapp

import com.hongguotv.core.RequestScope
import java.util.concurrent.Future

internal class AsyncWorkState {
    var generation: Int = 0
    var deferredWork: DeferredUi? = null
    var scope: RequestScope? = null
    var job: Future<*>? = null
}
