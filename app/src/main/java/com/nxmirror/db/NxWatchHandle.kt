package com.nxmirror.db

/**
 * Handle returned by [NxDatabase.watch]. Call [unsubscribe] to stop the
 * polling loop (the worker thread is a daemon and exits on its next wake-up).
 */
class NxWatchHandle internal constructor() {
    @Volatile
    internal var stopped: Boolean = false
        private set

    internal var worker: Thread? = null

    /** Stop the polling loop. Safe to call multiple times. */
    fun unsubscribe() {
        stopped = true
    }
}
