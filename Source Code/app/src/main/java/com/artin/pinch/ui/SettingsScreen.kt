package com.artin.pinch.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.artin.pinch.BuildConfig
import com.artin.pinch.data.Prefs

@Composable
fun SettingsScreen() {
    val p = LocalPal.current
    val s = LocalStr.current
    val t = LocalType.current
    val ctx = LocalContext.current
    val loc = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> Prefs.keepLocation = ok }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Header(s.tabSettings)
        Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().card(p.surface)) {
            Line(s.language) {
                Segmented(listOf("فارسی", "English"), if (Prefs.lang == "fa") 0 else 1, { Prefs.lang = if (it == 0) "fa" else "en" }, Modifier.width(172.dp))
            }
            Divider()
            Line(s.theme) {
                Segmented(listOf(s.auto, s.light, s.dark), Prefs.theme, { Prefs.theme = it }, Modifier.width(196.dp))
            }
            Divider()
            Line(s.videoCodec) {
                Segmented(listOf("HEVC", "H.264"), Prefs.codec, { Prefs.codec = it }, Modifier.width(172.dp))
            }
            Divider()
            Line(s.photoFormat) {
                Segmented(listOf("WebP", "JPEG"), Prefs.photo, { Prefs.photo = it }, Modifier.width(172.dp))
            }
            Divider()
            Line(s.keepLocation) {
                Toggle(Prefs.keepLocation) { on ->
                    if (on && ctx.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        loc.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
                    } else Prefs.keepLocation = on
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Txt(
            s.app + " " + s.digits(BuildConfig.VERSION_NAME), t.tiny, p.text2.copy(alpha = 0.7f),
            Modifier.fillMaxWidth().padding(bottom = 6.dp), align = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(LocalBarSpace.current))
    }
}

@Composable
private fun Line(label: String, control: @Composable () -> Unit) {
    val p = LocalPal.current
    val t = LocalType.current
    Row(
        Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(label, t.body, p.text, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        control()
    }
}

@Composable
private fun Divider(inset: Dp = 16.dp) {
    val p = LocalPal.current
    Spacer(Modifier.padding(horizontal = inset).fillMaxWidth().height(1.dp).background(p.line.copy(alpha = 0.6f)))
}
