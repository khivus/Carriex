package com.example.carriex.security

/** Memory only. A new process always starts locked; recreation keeps the current session. */
class LockSession {
    var authenticated = false
        private set
    var generation = 0L
        private set

    fun unlock() { authenticated = true; generation++ }
    fun lock() { authenticated = false; generation++ }
    fun background(enabled: Boolean, everyReturn: Boolean, changingConfiguration: Boolean) {
        if (enabled && everyReturn && !changingConfiguration) lock()
    }
    fun needsUnlock(enabled: Boolean) = enabled && !authenticated
}
