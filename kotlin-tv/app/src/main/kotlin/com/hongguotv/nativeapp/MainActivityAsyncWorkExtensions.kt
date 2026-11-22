// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import com.hongguotv.core.*

/**
 * Runs repository work off the UI thread and delivers results only to the
 * current Activity generation. Results arriving while backgrounded are deferred.
 */
internal fun <T> MainActivity.work(action: ()->T,done: (T)->Unit,failed: (Throwable)->Unit) {
    cancelWork()
    val ticket=generation; val scope=RequestScope(); workScope=scope
    workJob=io.submit {
        try {
            val result=scope.run(action)
            main.post {
                val discard={ if(result is RemoteVideo) result.close(); Unit }
                if(isDestroyed || generation!=ticket) discard()
                else if(foreground) done(result)
                else { deferredWork?.discard?.invoke(); deferredWork=DeferredUi({ done(result) },discard) }
            }
        } catch(e: Exception) {
            main.post {
                if(!isDestroyed && generation==ticket) {
                    if(foreground) failed(e) else deferredWork=DeferredUi({ failed(e) })
                }
            }
        }
    }
}
