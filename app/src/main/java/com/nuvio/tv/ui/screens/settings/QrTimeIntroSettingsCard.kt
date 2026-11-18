package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.ui.intro.QrTimeIntro

/** Appearance → Opening intro: turn the startup logo animation, or just its sound, on or off. */
@Composable
internal fun QrTimeIntroSettingsCard() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(QrTimeIntro.isEnabled(context)) }
    var sound by remember { mutableStateOf(QrTimeIntro.isSoundEnabled(context)) }

    SettingsGroupCard(
        modifier = Modifier.fillMaxWidth(),
        title = stringResource(R.string.qr_time_intro_section),
        subtitle = stringResource(R.string.qr_time_intro_section_subtitle)
    ) {
        SettingsToggleRow(
            title = stringResource(R.string.qr_time_intro_enabled),
            subtitle = stringResource(R.string.qr_time_intro_enabled_subtitle),
            checked = enabled,
            onToggle = {
                enabled = !enabled
                QrTimeIntro.setEnabled(context, enabled)
            }
        )
        SettingsToggleRow(
            title = stringResource(R.string.qr_time_intro_sound),
            subtitle = stringResource(R.string.qr_time_intro_sound_subtitle),
            checked = sound,
            enabled = enabled,
            onToggle = {
                sound = !sound
                QrTimeIntro.setSoundEnabled(context, sound)
            }
        )
    }
}
