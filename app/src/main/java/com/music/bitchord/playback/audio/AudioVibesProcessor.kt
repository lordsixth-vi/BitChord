package com.music.bitchord.playback.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

/** Which part of a stereo song the Audio Vibes "Voice" selector keeps. */
enum class VoiceMode {
    /** The song as recorded. */
    OFF,

    /** The centred voice taken out, for singing along. */
    INSTRUMENTAL,

    /** Only the middle of the mix, in the range a voice lives in. */
    VOCALS,
}

/**
 * What the Audio Vibes sheet has asked of the effects that live in the DSP
 * chain, as plain values the audio thread can read without a lock.
 *
 * Speed and pitch are not here: the player's own sink does those. This holds
 * only what [AudioVibesProcessor] needs. The playback service keeps it in step
 * with the settings, and every sink's processor reads it — so both players of a
 * crossfade always sound the same.
 */
object AudioVibesParams {
    /** Room mixed in, 0 (dry) to 1. */
    @Volatile
    var reverb: Float = 0f

    /** Muffled, warm sound, 0 (off) to 1. */
    @Volatile
    var lofi: Float = 0f

    /** Extra low end, 0 (off) to 1. */
    @Volatile
    var bass: Float = 0f

    @Volatile
    var voice: VoiceMode = VoiceMode.OFF
}

/**
 * The effects of the Audio Vibes sheet that work on the sound itself: Voice
 * (instrumental / vocals only), Bass Boost, Reverb Space and Lo-Fi Warmth.
 * In that order along the signal, so a karaoke track can still be given a room.
 *
 * ## Voice
 *
 * No separation model — a phone cannot run one live — but the oldest trick in
 * karaoke: a singer is usually mixed dead centre, so what is the *same* in both
 * channels is mostly voice, and what *differs* is mostly everything else.
 *
 *  - **Instrumental** subtracts the centre from both channels, but only above
 *    150 Hz, so the kick and bass guitar (also mixed centre) keep their body.
 *  - **Vocals only** keeps the centre alone and band-passes it to roughly
 *    200 Hz – 5 kHz, which is where a voice is and most of the rest is not.
 *
 * Both are approximations. They are at their best on a conventional stereo
 * mix and at their worst on one with a wide, doubled or mono vocal. Switching
 * fades out and back in rather than jumping, and a mono signal is left alone —
 * there is no centre to take out of one channel.
 *
 * ## Bass boost
 *
 * A low-passed copy of the signal added back on top of itself: everything under
 * about 110 Hz gets louder, nothing above it is touched.
 *
 * ## Reverb
 *
 * A Freeverb-style room: eight damped feedback delays in parallel, then four
 * all-pass stages to smear the echoes into a wash, with the right channel's
 * delays a little longer than the left's so the room has width. Cheap — a few
 * dozen multiplications per sample.
 *
 * ## Lo-fi warmth
 *
 * Two things a worn cassette does: it loses the top end, and it rounds off
 * peaks. A two-stage low-pass takes the treble down as the slider rises (from
 * out of earshot at 20 kHz to a "next door" 2.5 kHz at full), and a soft
 * saturator bends the loud parts instead of clipping them.
 *
 * ## Staying out of the way
 *
 * With everything off this returns before reading a sample, so the block leaves
 * the stage exactly as it arrived — see [DspChain] on why that matters. Changes
 * are ramped across a block rather than jumped to, so dragging a slider never
 * clicks.
 *
 * Stereo and mono only; anything wider passes through untouched.
 */
class AudioVibesProcessor : FloatAudioProcessor {

    private var sampleRate = 0
    private var channels = 0

    // ---- voice ----
    private var voiceMode = VoiceMode.OFF
    private var voiceWeight = 0f
    private var midLow = 0f
    private var vocalHpA = 0f
    private var vocalHpB = 0f
    private var vocalLpA = 0f
    private var vocalLpB = 0f
    private var coeffMidLow = 0f
    private var coeffVocalHp = 0f
    private var coeffVocalLp = 0f

    // ---- bass ----
    private var bassCurrent = 0f
    private var bassLpL = 0f
    private var bassLpR = 0f
    private var coeffBass = 0f

    // ---- reverb ----
    private var combL = emptyArray<Comb>()
    private var combR = emptyArray<Comb>()
    private var allpassL = emptyArray<Allpass>()
    private var allpassR = emptyArray<Allpass>()
    private var wetCurrent = 0f

    // ---- lo-fi ----
    private var lofiCurrent = 0f
    private var lpL1 = 0f
    private var lpL2 = 0f
    private var lpR1 = 0f
    private var lpR2 = 0f

    /** True once there is state in the filters that a bypass should clear. */
    private var dirty = false

    override fun configure(sampleRate: Int, channelCount: Int) {
        this.sampleRate = sampleRate
        this.channels = channelCount
        if (sampleRate <= 0 || channelCount !in 1..2) {
            combL = emptyArray()
            combR = emptyArray()
            allpassL = emptyArray()
            allpassR = emptyArray()
            return
        }
        coeffMidLow = onePole(MID_LOW_HZ)
        coeffVocalHp = onePole(VOCAL_LOW_HZ)
        coeffVocalLp = onePole(VOCAL_HIGH_HZ)
        coeffBass = onePole(BASS_HZ)

        val scale = sampleRate / REFERENCE_RATE
        combL = Array(COMB_TUNING.size) { Comb((COMB_TUNING[it] * scale).roundToInt().coerceAtLeast(2)) }
        combR = Array(COMB_TUNING.size) {
            Comb(((COMB_TUNING[it] + STEREO_SPREAD) * scale).roundToInt().coerceAtLeast(2))
        }
        allpassL = Array(ALLPASS_TUNING.size) { Allpass((ALLPASS_TUNING[it] * scale).roundToInt().coerceAtLeast(2)) }
        allpassR = Array(ALLPASS_TUNING.size) {
            Allpass(((ALLPASS_TUNING[it] + STEREO_SPREAD) * scale).roundToInt().coerceAtLeast(2))
        }
        clearState()
    }

    override fun process(block: AudioBlock) {
        val frames = block.frameCount
        if (frames == 0) return

        val stereo = block.channelCount == 2
        val wetTarget = AudioVibesParams.reverb.coerceIn(0f, 1f) * WET_GAIN
        val lofiTarget = AudioVibesParams.lofi.coerceIn(0f, 1f)
        val bassTarget = AudioVibesParams.bass.coerceIn(0f, 1f) * BASS_GAIN
        // Nothing to take out of a single channel.
        val voiceDesired = if (stereo) AudioVibesParams.voice else VoiceMode.OFF

        val reverbActive = wetTarget > ACTIVE_EPSILON || wetCurrent > ACTIVE_EPSILON
        val lofiActive = lofiTarget > ACTIVE_EPSILON || lofiCurrent > ACTIVE_EPSILON
        val bassActive = bassTarget > ACTIVE_EPSILON || bassCurrent > ACTIVE_EPSILON
        val voiceBusy = voiceMode != VoiceMode.OFF || voiceDesired != VoiceMode.OFF || voiceWeight > ACTIVE_EPSILON
        if (!reverbActive && !lofiActive && !bassActive && !voiceBusy) {
            // Settled at zero: leave the block alone, and forget the filters so
            // the next time one is switched on it does not replay an old tail.
            if (dirty) clearState()
            wetCurrent = 0f
            lofiCurrent = 0f
            bassCurrent = 0f
            voiceWeight = 0f
            voiceMode = VoiceMode.OFF
            return
        }
        if (block.channelCount != channels || channels !in 1..2 || combL.isEmpty()) return

        dirty = true
        val samples = block.samples

        // ---- voice: fade out, swap the mode while silent, fade in ----
        val voiceTarget: Float
        if (voiceMode != voiceDesired) {
            if (voiceWeight <= ACTIVE_EPSILON) {
                voiceMode = voiceDesired
                clearVoiceState()
                voiceTarget = if (voiceMode != VoiceMode.OFF) 1f else 0f
            } else {
                voiceTarget = 0f
            }
        } else {
            voiceTarget = if (voiceMode != VoiceMode.OFF) 1f else 0f
        }
        val voiceOn = stereo && (voiceMode != VoiceMode.OFF || voiceWeight > ACTIVE_EPSILON)
        var vw = voiceWeight
        val vwStep = (voiceTarget - vw) / frames

        // ---- bass, ramped across the block ----
        var bass = bassCurrent
        val bassStep = (bassTarget - bass) / frames

        // ---- lo-fi coefficients, eased toward the target once per block ----
        lofiCurrent += (lofiTarget - lofiCurrent) * LOFI_EASE
        if (lofiCurrent < ACTIVE_EPSILON && lofiTarget <= ACTIVE_EPSILON) lofiCurrent = 0f
        val lofi = lofiCurrent
        val lofiOn = lofi > 0f
        val cutoffHz = LOFI_OPEN_HZ * (LOFI_CLOSED_HZ / LOFI_OPEN_HZ).pow(lofi.toDouble())
        val lpCoeff = (1.0 - exp(-2.0 * PI * cutoffHz / sampleRate)).toFloat().coerceIn(0.001f, 1f)
        val drive = 1f + LOFI_DRIVE * lofi
        val makeup = (1f + LOFI_MAKEUP * lofi) / drive

        // ---- reverb mix, ramped linearly across the block ----
        val wetStep = (wetTarget - wetCurrent) / frames
        var wet = wetCurrent

        var lp1L = lpL1
        var lp2L = lpL2
        var lp1R = lpR1
        var lp2R = lpR2

        var i = 0
        for (frame in 0 until frames) {
            var left = samples[i]
            var right = if (stereo) samples[i + 1] else left

            if (voiceOn) {
                val dryL = left
                val dryR = right
                val mid = (left + right) * 0.5f
                when (voiceMode) {
                    VoiceMode.INSTRUMENTAL -> {
                        midLow += coeffMidLow * (mid - midLow)
                        val centre = (mid - midLow) * CENTRE_CUT
                        left -= centre
                        right -= centre
                    }
                    VoiceMode.VOCALS -> {
                        // Two high-pass stages under the voice, two low-pass over it.
                        vocalHpA += coeffVocalHp * (mid - vocalHpA)
                        val high1 = mid - vocalHpA
                        vocalHpB += coeffVocalHp * (high1 - vocalHpB)
                        val high2 = high1 - vocalHpB
                        vocalLpA += coeffVocalLp * (high2 - vocalLpA)
                        vocalLpB += coeffVocalLp * (vocalLpA - vocalLpB)
                        val voice = vocalLpB * VOCAL_GAIN
                        left = voice
                        right = voice
                    }
                    VoiceMode.OFF -> Unit
                }
                left = dryL + (left - dryL) * vw
                right = dryR + (right - dryR) * vw
                vw += vwStep
            }

            if (bassActive) {
                bassLpL += coeffBass * (left - bassLpL)
                left += bass * bassLpL
                if (stereo) {
                    bassLpR += coeffBass * (right - bassLpR)
                    right += bass * bassLpR
                }
                bass += bassStep
            }

            // Reverb before the lo-fi: a muffled tape machine plays *in* a room,
            // and the room is what the warmth then rolls off.
            if (reverbActive) {
                val input = (left + right) * REVERB_INPUT_GAIN
                var outL = 0f
                var outR = 0f
                for (c in combL.indices) {
                    outL += combL[c].process(input)
                    outR += combR[c].process(input)
                }
                for (a in allpassL.indices) {
                    outL = allpassL[a].process(outL)
                    outR = allpassR[a].process(outR)
                }
                // Dry is pulled back along with the room coming in, per sample,
                // so neither steps when a slider is dragged.
                val dry = 1f - DRY_DUCK * wet * INV_WET_GAIN
                left = left * dry + outL * wet
                right = right * dry + outR * wet
                wet += wetStep
            }

            if (lofiOn) {
                lp1L += lpCoeff * (left - lp1L)
                lp2L += lpCoeff * (lp1L - lp2L)
                left = softClip(lp2L * drive) * makeup
                if (stereo) {
                    lp1R += lpCoeff * (right - lp1R)
                    lp2R += lpCoeff * (lp1R - lp2R)
                    right = softClip(lp2R * drive) * makeup
                }
            }

            samples[i] = left
            if (stereo) {
                samples[i + 1] = right
                i += 2
            } else {
                i += 1
            }
        }

        voiceWeight = voiceTarget
        bassCurrent = bassTarget
        wetCurrent = wetTarget
        lpL1 = lp1L
        lpL2 = lp2L
        lpR1 = lp1R
        lpR2 = lp2R
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun flush() {
        clearState()
    }

    override fun reset() {
        combL = emptyArray()
        combR = emptyArray()
        allpassL = emptyArray()
        allpassR = emptyArray()
        wetCurrent = 0f
        lofiCurrent = 0f
        bassCurrent = 0f
        voiceWeight = 0f
        voiceMode = VoiceMode.OFF
        lpL1 = 0f
        lpL2 = 0f
        lpR1 = 0f
        lpR2 = 0f
        bassLpL = 0f
        bassLpR = 0f
        clearVoiceState()
        dirty = false
        sampleRate = 0
        channels = 0
    }

    private fun clearState() {
        for (c in combL) c.clear()
        for (c in combR) c.clear()
        for (a in allpassL) a.clear()
        for (a in allpassR) a.clear()
        lpL1 = 0f
        lpL2 = 0f
        lpR1 = 0f
        lpR2 = 0f
        bassLpL = 0f
        bassLpR = 0f
        clearVoiceState()
        dirty = false
    }

    private fun clearVoiceState() {
        midLow = 0f
        vocalHpA = 0f
        vocalHpB = 0f
        vocalLpA = 0f
        vocalLpB = 0f
    }

    /** Smoothing factor of a one-pole low-pass at [hz] for the current rate. */
    private fun onePole(hz: Double): Float =
        (1.0 - exp(-2.0 * PI * hz / sampleRate)).toFloat().coerceIn(0.0005f, 1f)

    /**
     * Bends peaks instead of clipping them. Unity slope at zero, flat at ±3, so
     * quiet passages pass unchanged and only the loud parts are rounded.
     */
    private fun softClip(x: Float): Float {
        val v = if (x > 3f) 3f else if (x < -3f) -3f else x
        return v * (27f + v * v) / (27f + 9f * v * v)
    }

    /** One damped feedback delay of the reverb. */
    private class Comb(size: Int) {
        private val buffer = FloatArray(size)
        private var index = 0
        private var store = 0f

        fun process(input: Float): Float {
            val out = buffer[index]
            store = out * DAMP_KEEP + store * DAMP
            buffer[index] = input + store * FEEDBACK
            if (++index >= buffer.size) index = 0
            return out
        }

        fun clear() {
            buffer.fill(0f)
            index = 0
            store = 0f
        }
    }

    /** One all-pass stage, smearing the echoes together. */
    private class Allpass(size: Int) {
        private val buffer = FloatArray(size)
        private var index = 0

        fun process(input: Float): Float {
            val buffered = buffer[index]
            val out = -input + buffered
            buffer[index] = input + buffered * 0.5f
            if (++index >= buffer.size) index = 0
            return out
        }

        fun clear() {
            buffer.fill(0f)
            index = 0
        }
    }

    private companion object {
        /** The rate the classic reverb tunings below are written for. */
        const val REFERENCE_RATE = 44100f

        val COMB_TUNING = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
        val ALLPASS_TUNING = intArrayOf(556, 441, 341, 225)

        /** Extra samples on the right channel's delays, for width. */
        const val STEREO_SPREAD = 23

        /** A long, hall-sized tail: about a second and a half to fade away. */
        const val FEEDBACK = 0.90f

        /** How much of each echo's top end is lost per trip round the delay. */
        const val DAMP = 0.2f
        const val DAMP_KEEP = 1f - DAMP

        /** Keeps eight summed delays from arriving louder than the song. */
        const val REVERB_INPUT_GAIN = 0.015f

        /** Slider to wet level: full slider is a deep, obvious room. */
        const val WET_GAIN = 1.5f
        const val INV_WET_GAIN = 1f / WET_GAIN

        /** How far the dry signal is pulled back as the room comes in. */
        const val DRY_DUCK = 0.3f

        const val LOFI_OPEN_HZ = 20_000.0
        const val LOFI_CLOSED_HZ = 2_500.0
        const val LOFI_DRIVE = 1.2f
        const val LOFI_MAKEUP = 0.35f

        /** How far each block closes the gap to the lo-fi target. */
        const val LOFI_EASE = 0.35f

        /** Below this the centre is left in, so kick and bass keep their body. */
        const val MID_LOW_HZ = 150.0

        /** How much of the centre is taken out; not all, which would also hollow the rest. */
        const val CENTRE_CUT = 0.92f

        /** The range of a voice, for Vocals only. */
        const val VOCAL_LOW_HZ = 200.0
        const val VOCAL_HIGH_HZ = 5_000.0

        /** What the narrow band loses, given back. */
        const val VOCAL_GAIN = 1.5f

        /** Where the bass boost stops reaching. */
        const val BASS_HZ = 110.0

        /** Slider to boost: full is about +8 dB at the bottom of the range. */
        const val BASS_GAIN = 1.5f

        const val ACTIVE_EPSILON = 0.001f
    }
}
