package com.rushify.mobile.ui

/** When the app asks for biometrics or the screen lock again after being in the background. */
object LockRules {
    /** Choices in More > Privacy & security: seconds in the background before locking, 0 meaning every time. */
    val timeouts = listOf(0 to "Immediately", 60 to "After 1 minute", 300 to "After 5 minutes", 900 to "After 15 minutes")
    const val DEFAULT_TIMEOUT_SEC = 60

    fun label(seconds: Int) = timeouts.firstOrNull { it.first == seconds }?.second ?: timeouts.first { it.first == DEFAULT_TIMEOUT_SEC }.second

    /**
     * True when coming back to the foreground should lock the app. [backgroundedAtMs] is when it was first sent away
     * (0 = it never left), so a system dialog or the unlock prompt itself, which never stops the activity, cannot lock it.
     */
    fun shouldLock(enabled: Boolean, loggedIn: Boolean, alreadyLocked: Boolean, backgroundedAtMs: Long, nowMs: Long, timeoutSec: Int): Boolean {
        if (!enabled || !loggedIn || alreadyLocked || backgroundedAtMs == 0L) return false
        return nowMs - backgroundedAtMs >= timeoutSec * 1000L
    }

    /** Whether the app starts locked: when the lock is on and someone is signed in. */
    fun startsLocked(enabled: Boolean, loggedIn: Boolean) = enabled && loggedIn
}
