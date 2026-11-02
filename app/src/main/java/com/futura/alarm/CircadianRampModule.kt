// app/src/main/java/com/futura/alarm/CircadianRampModule.kt
package com.futura.alarm

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Advanced chronobiological orchestration engine.
 *
 * Drives a coordinated multi-stage wake-up ramp that models the human
 * circadian response — not just "brightness goes up". The engine
 * produces four coordinated outputs at any instant in the ramp window:
 *
 *   1. **Luminance** 0..1 — a non-linear sigmoid (default) or
 *      exponential curve, matching what the eye perceives as a natural
 *      sunrise.
 *   2. **Kelvin** 1800K..6500K — colour temperature, interpolated from
 *      candlelight-amber at ramp start to mid-day-white at ramp end.
 *      Real sunrise colour shifts along this trajectory; the eye's
 *      pupillary response to blue-vs-amber is a major driver of how
 *      "wakey" a light feels.
 *   3. **Melatonin suppression** 0..1 — an estimate of how much the
 *      ramp has suppressed melatonin production at any instant, based
 *      on the published action spectrum (Brainard et al. 2001; Thapan
 *      et al. 2001). Used by the audio pipeline to decide when to
 *      *stop* being gentle.
 *   4. **Volume** 0..1 — the audio companion of luminance, phased
 *      slightly behind (auditory is more jarring than visual; a small
 *      lag makes the wake-up feel more natural).
 *
 * ── The three curves ────────────────────────────────────────────────
 *
 *   • **SIGMOID** (default) — `1 / (1 + e^(-k(t - 0.5)))`, with `k`
 *     scaled by aggression. Slow start, slow end, fast middle. This is
 *     the shape real sunrise luminance follows, and the shape most
 *     sleep research supports.
 *   • **EASE_IN** — `t^2`. Slow start, sharp finish. Good for users who
 *     need a "push" at the end of the ramp.
 *   • **EXPONENTIAL** — `1 - 2^(-10t)`. Very slow start, very sharp
 *     finish. For deep sleepers; the last 20% is the entire wake-up.
 *
 * ── Solar ephemeris ─────────────────────────────────────────────────
 *
 * If the user has enabled solar coupling and set a latitude/longitude,
 * the ramp window's *start* time is shifted so the ramp's midpoint
 * coincides with local sunrise (or a user-defined offset from sunrise,
 * typically 15–30 min before, to align peak luminance with natural
 * light). This is what "circadian schedule" means in practice — the
 * body's circadian clock is entrained by the solar day, not by a user
 * setting.
 *
 * ── Sleep inertia ───────────────────────────────────────────────────
 *
 * The [SleepInertiaProfile] is a coarse model of expected morning
 * grogginess, computed from three inputs the caller supplies:
 *   • Time the user last fell asleep (or a default from the alarm
 *     schedule).
 *   • Time the user woke yesterday (in the last 7 days).
 *   • Current day-of-week (weekend `sleep debt` is real and affects
 *     inertia).
 *
 * The heuristic doesn't pretend to be medical-grade. It biases the
 * ramp's *starting slope* — a user with high estimated inertia gets a
 * slower initial rise so they have more time to surface without a
 * jarring spike.
 *
 * Rule 6 — every computation wrapped in `try/catch`, returning the
 * caller's floor on error.
 * Rule 7 — all mutable state behind [mutex]; readers use `StateFlow`
 * (lock-free).
 */
@Singleton
class CircadianRampModule @Inject constructor() {
    private val tag = "CircadianRampModule"

    enum class Curve { SIGMOID, EASE_IN, EXPONENTIAL }

    /** Coarse estimate of the user's expected morning grogginess. */
    enum class SleepInertia { LOW, MODERATE, HIGH }

    data class GeoLocation(val latitude: Double, val longitude: Double)

    /**
     * Immutable description of one ramp window.
     *
     * @param startMs Epoch ms when luminance/audio begin to rise.
     * @param endMs Epoch ms when the ramp reaches 1.0.
     * @param curve Envelope family. See class KDoc.
     * @param aggression `[1.0, 4.0]` — matches `AlarmSpec.rampProfile`'s
     *        documented range. Higher = steeper mid-ramp.
     * @param startKelvin Colour temperature at ramp start (default
     *        1800K — candlelight).
     * @param endKelvin Colour temperature at ramp end (default 6500K
     *        — mid-day white).
     * @param location Optional lat/lon for solar ephemeris coupling.
     *        When set, [resolvedWindow] shifts the window so
     *        the ramp midpoint lands on local sunrise (see class KDoc).
     * @param solarOffsetMinutes Offset from sunrise; negative means "start
     *        before sunrise". Default -15 (peak luminance at sunrise).
     * @param inertia Caller-computed grogginess; biases the initial
     *        slope. See [deriveInertia].
     * @param audioLagFraction Fraction of the ramp window by which audio
     *        lags luminance. Default 0.08 (8% lag).
     */
    data class RampPlan(
        val startMs: Long,
        val endMs: Long,
        val curve: Curve = Curve.SIGMOID,
        val aggression: Float = 2.0f,
        val startKelvin: Int = 1800,
        val endKelvin: Int = 6500,
        val location: GeoLocation? = null,
        val solarOffsetMinutes: Int = -15,
        val inertia: SleepInertia = SleepInertia.MODERATE,
        val audioLagFraction: Float = 0.08f
    ) {
        init {
            require(endMs > startMs) { "endMs must be > startMs" }
            require(aggression in 1.0f..4.0f) {
                "aggression must be in [1.0, 4.0]"
            }
            require(startKelvin in 1000..10000) { "startKelvin out of range" }
            require(endKelvin in 1000..10000) { "endKelvin out of range" }
            require(audioLagFraction in 0f..0.5f) {
                "audioLagFraction must be in [0, 0.5]"
            }
        }

        /**
         * The window used for envelope evaluation. With [location] set, both
         * ends move by the same amount, so the ramp midpoint lands on
         * sunrise + [solarOffsetMinutes] and the duration is unchanged.
         * Returns (startMs, endMs) when there is no location or the
         * ephemeris fails. Depends only on this plan's fields, so the
         * result does not change over time.
         */
        fun resolvedWindow(): Pair<Long, Long> {
            val loc = location ?: return startMs to endMs
            return try {
                val sunrise = SolarEphemeris.sunriseMs(
                    loc.latitude, loc.longitude, startMs
                ) ?: return startMs to endMs
                val targetMid = sunrise + solarOffsetMinutes * 60_000L
                val duration = endMs - startMs
                val newStart = targetMid - duration / 2
                newStart to (newStart + duration)
            } catch (e: Exception) {
                startMs to endMs
            }
        }
    }

    /** Output snapshot at a given moment. */
    data class Sample(
        val luminance: Float,
        val volume: Float,
        val kelvin: Int,
        val melatoninSuppression: Float,
        val inertia: SleepInertia
    )

    private val mutex = Mutex()
    private val _plan = MutableStateFlow<RampPlan?>(null)
    val plan: StateFlow<RampPlan?> = _plan.asStateFlow()

    private val _sample = MutableStateFlow(
        Sample(luminance = 0f, volume = 0f, kelvin = 1800,
            melatoninSuppression = 0f, inertia = SleepInertia.MODERATE)
    )
    val sample: StateFlow<Sample> = _sample.asStateFlow()

    suspend fun start(newPlan: RampPlan) = mutex.withLock {
        // Decide the window once, here, so it cannot change between ticks.
        // A sunrise-aligned window is used only if it does not start more
        // than a minute in the past; otherwise the requested window stands.
        val (resolvedStart, resolvedEnd) = newPlan.resolvedWindow()
        val useShifted = resolvedStart >= System.currentTimeMillis() - 60_000L
        val effective = if (useShifted) {
            newPlan.copy(startMs = resolvedStart, endMs = resolvedEnd, location = null)
        } else {
            newPlan.copy(location = null)
        }
        _plan.value = effective
        Log.i(
            tag,
            "Ramp start=${effective.startMs} end=${effective.endMs} " +
                "curve=${effective.curve} aggression=${effective.aggression} " +
                "kelvin=${effective.startKelvin}→${effective.endKelvin} " +
                "inertia=${effective.inertia} shifted=$useShifted"
        )
        // Prime the sample with the value at start.
        _sample.value = computeSample(effective, effective.startMs)
    }

    suspend fun stop() = mutex.withLock {
        if (_plan.value != null) Log.i(tag, "Ramp stopped")
        _plan.value = null
    }

    // ── Public readers ─────────────────────────────────────────────

    /** Envelope value 0..1 at [nowMs] (luminance). */
    fun currentLuminance(nowMs: Long = System.currentTimeMillis()): Float =
        computeSample(_plan.value, nowMs).luminance

    /** Audio envelope value 0..1 at [nowMs], lagging luminance. */
    fun currentVolume(nowMs: Long = System.currentTimeMillis()): Float =
        computeSample(_plan.value, nowMs).volume

    /** Colour temperature at [nowMs], in Kelvin. */
    fun currentKelvin(nowMs: Long = System.currentTimeMillis()): Int =
        computeSample(_plan.value, nowMs).kelvin

    /** Melatonin-suppression estimate 0..1 at [nowMs]. */
    fun melatoninSuppressionEstimate(
        nowMs: Long = System.currentTimeMillis()
    ): Float = computeSample(_plan.value, nowMs).melatoninSuppression

    /** Full snapshot at [nowMs]. Called on each tick by the audio and
     *  brightness pipelines. */
    fun snapshot(nowMs: Long = System.currentTimeMillis()): Sample {
        val s = computeSample(_plan.value, nowMs)
        _sample.value = s
        return s
    }

    // ── Core math ──────────────────────────────────────────────────

    private fun computeSample(plan: RampPlan?, nowMs: Long): Sample {
        if (plan == null) return Sample(0f, 0f, 1800, 0f, SleepInertia.MODERATE)
        return try {
            val startMs = plan.startMs
            if (nowMs <= startMs) {
                return Sample(
                    luminance = 0f,
                    volume = 0f,
                    kelvin = plan.startKelvin,
                    melatoninSuppression = 0f,
                    inertia = plan.inertia
                )
            }
            if (nowMs >= plan.endMs) {
                return Sample(
                    luminance = 1f,
                    volume = 1f,
                    kelvin = plan.endKelvin,
                    melatoninSuppression = 1f,
                    inertia = plan.inertia
                )
            }

            val durationMs = (plan.endMs - startMs).toDouble()
            val elapsedMs = (nowMs - startMs).toDouble()
            val t = (elapsedMs / durationMs).coerceIn(0.0, 1.0)

            val luminance = shape(plan.curve, plan.aggression, plan.inertia, t)
            val audioT = (t - plan.audioLagFraction)
                .coerceIn(0.0, 1.0)
            val volume = shape(plan.curve, plan.aggression, plan.inertia, audioT)

            val kelvin = interpolateKelvin(
                plan.startKelvin, plan.endKelvin, luminance.toFloat()
            )

            val suppression = estimateMelatoninSuppression(
                luminance.toFloat(), kelvin
            )

            Sample(
                luminance = luminance.toFloat().coerceIn(0f, 1f),
                volume = volume.toFloat().coerceIn(0f, 1f),
                kelvin = kelvin,
                melatoninSuppression = suppression,
                inertia = plan.inertia
            )
        } catch (e: Exception) {
            Log.e(tag, "computeSample failed", e)
            Sample(0f, 0f, 1800, 0f, SleepInertia.MODERATE)
        }
    }

    /**
     * Shape [t] through the curve family, with aggression applied as
     * a complement-power (see file header). Sleep inertia biases the
     * start-slope: HIGH inertia slows the initial rise (so the user is
     * not yanked awake by a bright flash), LOW inertia allows a faster
     * start.
     */
    private fun shape(
        curve: Curve,
        aggression: Float,
        inertia: SleepInertia,
        t: Double
    ): Double {
        // Inertia bias: t^e maps 0→0 and 1→1, so the ramp stays continuous
        // with the hold at full level. e < 1 starts faster (LOW), e > 1
        // starts slower (HIGH).
        val exponent = when (inertia) {
            SleepInertia.LOW -> 0.87
            SleepInertia.MODERATE -> 1.00
            SleepInertia.HIGH -> 1.22
        }
        val te = t.pow(exponent)

        val curveValue: Double = when (curve) {
            Curve.SIGMOID -> {
                // Steepness k scales with aggression: at k=8 the curve
                // transitions in ~±0.15 of the window; at k=4 the
                // transition spans ~±0.30.
                val k = 4.0 + (aggression - 1.0) * 1.5  // 4.0 .. 8.5
                1.0 / (1.0 + kotlin.math.exp(-k * (te - 0.5)))
            }
            Curve.EASE_IN -> te * te
            Curve.EXPONENTIAL -> 1.0 - 2.0.pow(-10.0 * te)
        }

        // Re-normalise so that curve(0) == 0 and curve(1) == 1 — the
        // sigmoid has curve(0) > 0 and curve(1) < 1 without this.
        val zeroValue = when (curve) {
            Curve.SIGMOID -> {
                val k = 4.0 + (aggression - 1.0) * 1.5
                1.0 / (1.0 + kotlin.math.exp(k * 0.5))
            }
            else -> 0.0
        }
        val oneValue = when (curve) {
            Curve.SIGMOID -> {
                val k = 4.0 + (aggression - 1.0) * 1.5
                1.0 / (1.0 + kotlin.math.exp(-k * 0.5))
            }
            Curve.EASE_IN -> 1.0
            Curve.EXPONENTIAL -> 1.0 - 2.0.pow(-10.0)
        }
        val normalised = if (oneValue > zeroValue) {
            (curveValue - zeroValue) / (oneValue - zeroValue)
        } else curveValue

        // Aggression on the complement (matches AlarmSpec.rampProfile
        // semantics used elsewhere in the codebase).
        val complemented = (1.0 - normalised).coerceIn(0.0, 1.0)
        val shaped = 1.0 - complemented.pow(aggression.toDouble())
        return shaped.coerceIn(0.0, 1.0)
    }

    // ── Kelvin interpolation ───────────────────────────────────────

    private fun interpolateKelvin(start: Int, end: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        val k = start + ((end - start) * f).roundToInt()
        return k.coerceIn(1000, 10000)
    }

    // ── Melatonin suppression model ────────────────────────────────
    //
    // Coarse estimate based on the published action spectrum: melatonin
    // suppression is driven primarily by short-wavelength (blue) light,
    // with peak sensitivity at ~460–490 nm — which corresponds to the
    // blue end of the Kelvin scale. Our model:
    //
    //     suppression = luminance^0.5 × blueBias(kelvin)
    //
    // where `blueBias` is a sigmoid-like ramp from ~0.15 at 1800K
    // (candlelight — mostly red/amber) to ~1.0 at 6500K (mid-day white).
    // This matches the empirical observation that warm light at high
    // intensity does NOT suppress melatonin nearly as much as cool light
    // at the same intensity.
    //
    // The result is 0..1; it is an *estimate* — real quantification
    // requires a spectrophotometer. This model is adequate for UX
    // decisions like "delay the audio ramp until melatonin suppression
    // crosses X".
    private fun estimateMelatoninSuppression(
        luminance: Float,
        kelvin: Int
    ): Float {
        if (luminance <= 0f) return 0f
        // Map kelvin 1800..6500 to a 0..1 blue-content scale.
        val kNorm = ((kelvin - 1800).toFloat() / (6500f - 1800f))
            .coerceIn(0f, 1f)
        // Blue bias: at 1800K → 0.15; at 6500K → 1.0. A slight ease
        // makes the "cooler = more suppression" relationship smooth
        // rather than linear.
        val blueBias = 0.15f + 0.85f * (kNorm * kNorm)
        // Suppression grows sub-linearly with luminance because the
        // melanopsin pathway saturates.
        val luminanceComponent = kotlin.math.sqrt(luminance)
        return (luminanceComponent * blueBias).coerceIn(0f, 1f)
    }

    // ── Sleep inertia heuristic ────────────────────────────────────

    /**
     * Compute a coarse inertia class from three caller-supplied inputs.
     * The result feeds [RampPlan.inertia].
     *
     * @param sleepHoursMinutes Last night's sleep duration in minutes.
     * @param avgSleepHoursMinutes 7-day average sleep duration in minutes.
     *        Used for sleep-debt estimation.
     * @param dayOfWeek 1 = Monday, 7 = Sunday. Weekends have higher
     *        inertia on average (sleep-phase shift).
     */
    fun deriveInertia(
        sleepHoursMinutes: Int,
        avgSleepHoursMinutes: Int,
        dayOfWeek: Int
    ): SleepInertia {
        return try {
            val debtMinutes = (avgSleepHoursMinutes - sleepHoursMinutes)
                .coerceAtLeast(0)
            val isWeekend = dayOfWeek == 6 || dayOfWeek == 7
            val score = when {
                sleepHoursMinutes >= 7 * 60 && debtMinutes < 30 -> 0
                sleepHoursMinutes >= 6 * 60 && debtMinutes < 60 -> 1
                sleepHoursMinutes < 5 * 60 || debtMinutes >= 120 -> 3
                else -> 2
            } + if (isWeekend) 1 else 0
            when {
                score <= 1 -> SleepInertia.LOW
                score <= 3 -> SleepInertia.MODERATE
                else -> SleepInertia.HIGH
            }
        } catch (e: Exception) {
            Log.e(tag, "deriveInertia failed", e)
            SleepInertia.MODERATE
        }
    }

    // ── Range mapping (unchanged from prior version) ───────────────

    fun mapToRange(normalized: Float, floor: Float, ceiling: Float): Float {
        val n = normalized.coerceIn(0f, 1f)
        return floor + n * (ceiling - floor)
    }

    // ══════════════════════════════════════════════════════════════════
    // Solar ephemeris (embedded; no external dependencies)
    // ══════════════════════════════════════════════════════════════════
    //
    // NOAA solar calculator formulas, simplified for local-sunrise
    // computation. Accuracy is ~±2 minutes at mid-latitudes, which is
    // vastly better than the ramp resolution we need.
    //
    // Reference: Meeus, "Astronomical Algorithms", ch. 25; NOAA's
    // publicly-documented spreadsheet implementation of the same.

    object SolarEphemeris {
        /**
         * Compute the UTC epoch ms of the next sunrise at
         * (latitude, longitude) on or after [referenceMs]. Returns null
         * on polar day / polar night / invalid inputs.
         */
        fun sunriseMs(
            latitude: Double,
            longitude: Double,
            referenceMs: Long
        ): Long? {
            return try {
                if (latitude !in -90.0..90.0) return null
                if (longitude !in -180.0..180.0) return null

                // Work in days since 2000-01-01 12:00 UT (J2000 epoch).
                // Integer day number counted from J2000 noon (NOAA's `n`).
                val refDays = Math.floor(referenceMs / 86_400_000.0 - 10957.0)

                for (dayOffset in 0..2) {
                    val n = refDays + dayOffset
                    val sunrise = computeSunrise(latitude, longitude, n)
                    if (sunrise == null) continue
                    val sunriseMs = ((sunrise + 10957.5) *
                        86_400_000.0).toLong()
                    if (sunriseMs >= referenceMs) return sunriseMs
                }
                null
            } catch (e: Exception) {
                null
            }
        }

        // Compute UTC hours of sunrise for the given day-number n.
        private fun computeSunrise(
            latitude: Double,
            longitude: Double,
            n: Double
        ): Double? {
            // Mean solar noon (in days since J2000).
            val jStar = n - longitude / 360.0

            // Solar mean anomaly (degrees).
            val M = (357.5291 + 0.98560028 * jStar).mod(360.0)

            // Equation of center.
            val C = 1.9148 * sin(Math.toRadians(M)) +
                    0.0200 * sin(Math.toRadians(2 * M)) +
                    0.0003 * sin(Math.toRadians(3 * M))

            // Ecliptic longitude.
            val lambda = (M + C + 180.0 + 102.9372).mod(360.0)

            // Solar transit (days since J2000).
            val jTransit = jStar +
                0.0053 * sin(Math.toRadians(M)) -
                0.0069 * sin(Math.toRadians(2 * lambda))

            // Solar declination.
            val sinDec = sin(Math.toRadians(lambda)) *
                sin(Math.toRadians(23.4397))
            val cosDec = cos(asin(sinDec))

            // Hour angle for sunrise at standard altitude -0.833° (the
            // sun is "up" when its upper limb, refracted, is at horizon).
            val cosH0 = (sin(Math.toRadians(-0.833)) -
                    sin(Math.toRadians(latitude)) * sinDec) /
                (cos(Math.toRadians(latitude)) * cosDec)

            // Polar day / night.
            if (cosH0 < -1.0 || cosH0 > 1.0) return null

            // Sunrise hour angle, in degrees.
            val h0 = Math.toDegrees(kotlin.math.acos(cosH0))

            return jTransit - h0 / 360.0
        }

        private fun Double.mod(m: Double): Double {
            val r = this % m
            return if (r < 0) r + m else r
        }
    }
}