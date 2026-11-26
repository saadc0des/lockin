package com.saad.lockin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After a reboot, bring back the timer notification if a session is still running, and the PC link if it is on.
 *  (The lock itself comes back by itself: Android restarts enabled accessibility services.) */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (LockStore.sessionActive(c, System.currentTimeMillis())) LockService.start(c)
        LinkService.start(c)
    }
}
