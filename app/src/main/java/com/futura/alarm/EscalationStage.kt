// app/src/main/java/com/futura/alarm/EscalationStage.kt
package com.futura.alarm

/**
 * Five progressive escalation tiers used by
 * [MissedAlarmWatchdog] when an alarm remains unanswered.
 *
 * Enum ordinal matches the escalation progression:
 *   0 = Gentle   — notification re-post, no intervention
 *   1 = Voice    — TTS nudges the user
 *   2 = Volume   — max volume + vibrate
 *   3 = Screen   — wake screen, full-screen alarm UI
 *   4 = Siren    — critical-level notification, sustained vibration
 */
enum class EscalationStage(val nativeValue: Int) {
    GENTLE(0),
    VOICE(1),
    VOLUME(2),
    SCREEN(3),
    SIREN(4);

    val isTerminal: Boolean get() = this == SIREN

    fun next(): EscalationStage = when (this) {
        GENTLE -> VOICE
        VOICE -> VOLUME
        VOLUME -> SCREEN
        SCREEN -> SIREN
        SIREN -> SIREN
    }

    companion object {
        fun fromNative(value: Int): EscalationStage =
            entries.firstOrNull { it.nativeValue == value } ?: GENTLE
    }
}
