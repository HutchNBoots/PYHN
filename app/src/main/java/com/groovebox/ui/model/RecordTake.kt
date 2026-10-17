package com.groovebox.ui.model

/**
 * When a live-record take's undo step opens and closes (build-20). It opens when Record is
 * armed. Disarming does not close it at once: the engine may still report notes it recorded
 * before it saw the disarm, so it stays open until the engine's transport reports itself
 * disarmed and one more frame of recorded events has been applied. Pure Kotlin.
 */
class RecordTake {
    enum class Action { None, Open, CloseAndOpen }

    var open = false
        private set
    private var closing = false
    private var disarmedFrames = 0

    /** Record's arm changed in the UI; returns what to do with the history. */
    fun armed(on: Boolean): Action {
        if (!on) {
            if (open) {
                closing = true
                disarmedFrames = 0
            }
            return Action.None
        }
        if (open && !closing) return Action.None
        val wasOpen = open
        open = true
        closing = false
        return if (wasOpen) Action.CloseAndOpen else Action.Open
    }

    /** A frame's recorded events were applied; true when the take's group should close now. */
    fun frameApplied(engineArmed: Boolean): Boolean {
        if (!open || !closing || engineArmed) return false
        disarmedFrames++
        if (disarmedFrames < 2) return false
        open = false
        closing = false
        return true
    }

    /** Close now (e.g. the app stops): true when a take was open. */
    fun forceClose(): Boolean {
        val was = open
        open = false
        closing = false
        return was
    }
}
