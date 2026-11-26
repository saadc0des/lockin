package com.saad.lockin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** The two buttons on the "Your PC started a locked session" notification. */
class LinkActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            Link.ACTION_ACCEPT -> Link.accept(c)?.let { Link.notifyError(c, it) }
            Link.ACTION_DECLINE -> Link.decline(c)
        }
    }
}
