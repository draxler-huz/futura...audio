// app/src/main/java/com/futura/tts/MorningDigestBuilder.kt
package com.futura.tts

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the plain-text briefing spoken by [TextToSpeechWrapper] at
 * wake-up time.
 *
 * The digest is composed of up to four sections, each optional based on
 * what the caller supplies:
 *
 *   1. **Greeting**: time-of-day-aware ("Good morning") plus an optional
 *      user name.
 *   2. **Weather**: supplied by the caller as a plain string. The builder
 *      does not call any weather API itself.
 *   3. **Calendar**: a list of event descriptions ("team standup at 9",
 *      "dentist at 2:30pm"). The builder does not read the calendar
 *      provider itself.
 *   4. **Alarm note**: a reminder that the alarm fired. Off by default; the
 *      caller can opt in.
 *
 * Today the caller passes in already-resolved strings and events. This keeps
 * the class testable and free of network or permission dependencies.
 */
@Singleton
class MorningDigestBuilder @Inject constructor() {
    private val tag = "MorningDigestBuilder"

    data class Inputs(
        val userName: String? = null,
        val weatherSummary: String? = null,
        val calendarEvents: List<String> = emptyList(),
        val mentionAlarm: Boolean = false,
        val hourOfDay: Int = java.util.Calendar.getInstance()
            .get(java.util.Calendar.HOUR_OF_DAY)
    )

    /**
     * Build the full briefing text.
     *
     * @return Non-empty plain text ready for [TextToSpeechWrapper.speak].
     *         The builder always returns at least the greeting, so a
     *         speak() call always has something to say.
     */
    fun build(inputs: Inputs): String {
        return try {
            val parts = mutableListOf<String>()

            // Greeting.
            val greeting = when (inputs.hourOfDay) {
                in 0..4 -> "Good night"
                in 5..11 -> "Good morning"
                in 12..17 -> "Good afternoon"
                else -> "Good evening"
            }
            parts.add(
                inputs.userName?.takeIf { it.isNotBlank() }?.let {
                    "$greeting, $it"
                } ?: greeting
            )

            // Alarm confirmation.
            if (inputs.mentionAlarm) {
                parts.add("Your alarm is ringing.")
            }

            // Weather.
            inputs.weatherSummary?.takeIf { it.isNotBlank() }?.let {
                parts.add("Weather: $it.")
            }

            // Calendar events.
            if (inputs.calendarEvents.isNotEmpty()) {
                val count = inputs.calendarEvents.size
                val header = if (count == 1) {
                    "You have one event today"
                } else {
                    "You have $count events today"
                }
                val names = inputs.calendarEvents.map { it.trim().removeSuffix(".") }
                val joined = if (names.size == 1) {
                    names[0]
                } else {
                    names.dropLast(1).joinToString(separator = ", ") + ", and " + names.last()
                }
                parts.add("$header: $joined.")
            }

            parts.joinToString(separator = " ").trim()
        } catch (e: Exception) {
            Log.e(tag, "build failed", e)
            "Good morning."
        }
    }

    /** Builds the briefing and speaks it in one call. */
    suspend fun buildAndSpeak(
        inputs: Inputs,
        tts: TextToSpeechWrapper
    ): Boolean {
        val text = build(inputs)
        return tts.speak(text)
    }
}
