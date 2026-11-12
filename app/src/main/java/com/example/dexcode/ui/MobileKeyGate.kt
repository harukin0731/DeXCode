package com.example.dexcode.ui

/** Invalidates asynchronous responses after cancellation, repeat release or a timeout. UI-thread only. */
class MobileKeyGate {
    private var generation = 0
    private var pending = false
    private var repeatEpoch: Int? = null
    fun begin(repeat: Int? = null): Int? {
        if (pending) return null
        pending = true
        repeatEpoch = repeat
        return ++generation
    }
    fun accepts(ticket: Int, currentRepeat: Int) = pending && ticket == generation && (repeatEpoch == null || repeatEpoch == currentRepeat)
    fun finish(ticket: Int): Boolean {
        if (!pending || generation != ticket) return false
        pending = false
        repeatEpoch = null
        return true
    }
    fun cancel() { generation++; pending = false; repeatEpoch = null }
    fun cancelRepeat(): Boolean {
        if (repeatEpoch == null) return false
        cancel()
        return true
    }
}
