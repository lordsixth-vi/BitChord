package com.music.bitchord.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.audio.VoiceMode
import com.music.bitchord.ui.icons.SfIcons
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One ready-made combination of the four Audio Vibes controls.
 *
 * Tapping a preset only sets the sliders — they stay live afterwards, so a
 * preset is a starting point rather than a mode. A preset reads as selected
 * for exactly as long as the sliders still sit where it put them.
 */
private data class VibesPreset(
    val nameRes: Int,
    val detailRes: Int,
    val speed: Float,
    val pitch: Float,
    val reverb: Float,
    val lofi: Float,
    val bass: Float = 0f,
    val voice: VoiceMode = VoiceMode.OFF,
) {
    fun matches(
        speed: Float,
        pitch: Float,
        reverb: Float,
        lofi: Float,
        bass: Float,
        voice: VoiceMode,
    ): Boolean =
        abs(this.speed - speed) < TOLERANCE &&
            abs(this.pitch - pitch) < TOLERANCE &&
            abs(this.reverb - reverb) < TOLERANCE &&
            abs(this.lofi - lofi) < TOLERANCE &&
            abs(this.bass - bass) < TOLERANCE &&
            this.voice == voice

    private companion object {
        const val TOLERANCE = 0.006f
    }
}

private val VIBES_PRESETS = listOf(
    VibesPreset(R.string.vibes_preset_slowed, R.string.vibes_preset_slowed_detail, 0.85f, 0.85f, 0.40f, 0f),
    VibesPreset(R.string.vibes_preset_rainy, R.string.vibes_preset_rainy_detail, 0.92f, 0.92f, 0.30f, 0.70f),
    VibesPreset(R.string.vibes_preset_nightcore, R.string.vibes_preset_nightcore_detail, 1.25f, 1.25f, 0.15f, 0f),
    VibesPreset(
        R.string.vibes_preset_karaoke, R.string.vibes_preset_karaoke_detail,
        1f, 1f, 0.15f, 0f, voice = VoiceMode.INSTRUMENTAL,
    ),
    VibesPreset(
        R.string.vibes_preset_acapella, R.string.vibes_preset_acapella_detail,
        1f, 1f, 0.25f, 0f, voice = VoiceMode.VOCALS,
    ),
    VibesPreset(
        R.string.vibes_preset_bass, R.string.vibes_preset_bass_detail,
        1f, 1f, 0f, 0f, bass = 0.7f,
    ),
)

/**
 * The player's Audio Vibes sheet: a few presets, and four sliders — speed,
 * pitch, reverb and lo-fi warmth — that change the sound of whatever is
 * playing, live. Everything is remembered until reset.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioVibesSheet(onDismiss: () -> Unit) {
    val speed by AppSettings.playbackSpeed.collectAsStateWithLifecycle()
    val pitch by AppSettings.pitchScale.collectAsStateWithLifecycle()
    val reverb by AppSettings.reverbAmount.collectAsStateWithLifecycle()
    val lofi by AppSettings.lofiAmount.collectAsStateWithLifecycle()
    val bass by AppSettings.bassBoost.collectAsStateWithLifecycle()
    val voice by AppSettings.voiceMode.collectAsStateWithLifecycle()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            VibesHeader(onReset = AppSettings::resetAudioVibes, onClose = onDismiss)

            Spacer(Modifier.height(20.dp))
            SectionLabel(stringResource(R.string.vibes_presets))
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                VIBES_PRESETS.forEach { preset ->
                    PresetCard(
                        preset = preset,
                        selected = preset.matches(speed, pitch, reverb, lofi, bass, voice),
                        onClick = {
                            AppSettings.setAudioVibes(
                                speed = preset.speed,
                                pitch = preset.pitch,
                                reverb = preset.reverb,
                                lofi = preset.lofi,
                                bass = preset.bass,
                                voice = preset.voice,
                            )
                        },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel(stringResource(R.string.vibes_voice))
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VoiceChip(
                    label = stringResource(R.string.vibes_voice_original),
                    selected = voice == VoiceMode.OFF,
                    onClick = { AppSettings.setVoiceMode(VoiceMode.OFF) },
                    modifier = Modifier.weight(1f),
                )
                VoiceChip(
                    label = stringResource(R.string.vibes_voice_instrumental),
                    selected = voice == VoiceMode.INSTRUMENTAL,
                    onClick = { AppSettings.setVoiceMode(VoiceMode.INSTRUMENTAL) },
                    modifier = Modifier.weight(1f),
                )
                VoiceChip(
                    label = stringResource(R.string.vibes_voice_vocals),
                    selected = voice == VoiceMode.VOCALS,
                    onClick = { AppSettings.setVoiceMode(VoiceMode.VOCALS) },
                    modifier = Modifier.weight(1f),
                )
            }
            if (voice != VoiceMode.OFF) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.vibes_voice_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel(stringResource(R.string.vibes_fine_tuning))
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                VibesSlider(
                    title = stringResource(R.string.vibes_speed),
                    valueLabel = timesLabel(speed),
                    value = speed,
                    range = AppSettings.VIBES_SPEED_MIN..AppSettings.VIBES_SPEED_MAX,
                    onChange = { AppSettings.setPlaybackSpeed(it.roundTo(100f)) },
                )
                VibesSlider(
                    title = stringResource(R.string.vibes_pitch),
                    valueLabel = timesLabel(pitch),
                    value = pitch,
                    range = AppSettings.VIBES_PITCH_MIN..AppSettings.VIBES_PITCH_MAX,
                    onChange = { AppSettings.setPitchScale(it.roundTo(100f)) },
                )
                VibesSlider(
                    title = stringResource(R.string.vibes_bass),
                    valueLabel = if (bass <= 0f) stringResource(R.string.vibes_off) else percentLabel(bass),
                    value = bass,
                    range = 0f..1f,
                    onChange = { AppSettings.setBassBoost(it.roundTo(100f)) },
                )
                VibesSlider(
                    title = stringResource(R.string.vibes_reverb),
                    valueLabel = percentLabel(reverb),
                    value = reverb,
                    range = 0f..1f,
                    onChange = { AppSettings.setReverbAmount(it.roundTo(100f)) },
                )
                VibesSlider(
                    title = stringResource(R.string.vibes_lofi),
                    valueLabel = if (lofi <= 0f) stringResource(R.string.vibes_off) else percentLabel(lofi),
                    value = lofi,
                    range = 0f..1f,
                    onChange = { AppSettings.setLofiAmount(it.roundTo(100f)) },
                )
            }
        }
    }
}

@Composable
private fun VibesHeader(onReset: () -> Unit, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = SfIcons.GraphicEq,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 14.dp),
        ) {
            Text(
                text = stringResource(R.string.audio_vibes),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.audio_vibes_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onReset) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = stringResource(R.string.vibes_reset),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        IconButton(onClick = onClose) {
            Icon(
                imageVector = SfIcons.Close,
                contentDescription = stringResource(R.string.close),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(Locale.getDefault()),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun PresetCard(preset: VibesPreset, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        },
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text(
                text = stringResource(preset.nameRes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(preset.detailRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** One of the three Voice choices — Original, Instrumental, Vocals only. */
@Composable
private fun VoiceChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        },
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun VibesSlider(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    ) {
        Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = valueLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Slider(
                value = value.coerceIn(range.start, range.endInclusive),
                onValueChange = onChange,
                valueRange = range,
            )
        }
    }
}

/** Rounds to a step of 1/[perUnit], so a dragged slider settles on tidy numbers like 0.85. */
private fun Float.roundTo(perUnit: Float): Float = (this * perUnit).roundToInt() / perUnit

private fun timesLabel(value: Float): String = String.format(Locale.getDefault(), "%.2f×", value)

private fun percentLabel(value: Float): String = "${(value * 100).roundToInt()}%"
