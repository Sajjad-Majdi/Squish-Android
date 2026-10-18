package com.artin.pinch.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.artin.pinch.data.Entry
import com.artin.pinch.data.History
import com.artin.pinch.data.Prefs
import kotlinx.coroutines.delay

@Composable
fun HistoryScreen() {
    val p = LocalPal.current
    val s = LocalStr.current
    val t = LocalType.current
    val space = LocalBarSpace.current
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(confirm) { if (confirm) { delay(3000); confirm = false } }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Header(s.tabHistory) {
            if (History.entries.isNotEmpty()) {
                if (confirm) PillButton(s.clearAll, { History.clear(); confirm = false }, height = 40.dp, icon = Ic.trash)
                else IconBtn(Ic.trash, { confirm = true })
            }
        }
        if (History.entries.isEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Ic.history, p.line, size = 44.dp)
                Spacer(Modifier.height(10.dp))
                Txt(s.historyEmpty, t.body, p.text2)
            }
            Spacer(Modifier.height(space))
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = space + 8.dp),
            ) {
                items(History.entries, key = { it.time.toString() + it.uri }) { EntryRow(it, it !== History.entries.last()) }
            }
        }
    }
}

@Composable
private fun EntryRow(e: Entry, divider: Boolean) {
    val p = LocalPal.current
    val s = LocalStr.current
    val t = LocalType.current
    val ctx = LocalContext.current
    val mime = if (e.video) "video/*" else "image/*"
    ListRow(divider, onClick = { openUri(ctx, e.uri, mime) }) {
        Thumb(e.uri, e.video, Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt(e.name, t.body, p.text)
            Txt(s.size(e.inSize) + s.arrow + s.size(e.outSize), t.small, p.text2)
            if (e.score >= 0f) QualityTag(e.score, Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.clip(RoundedCornerShape(50)).background(p.good.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 5.dp),
        ) { Txt(s.drop(1f - e.outSize.toFloat() / e.inSize.coerceAtLeast(1)), t.tiny, p.good) }
        Spacer(Modifier.width(4.dp))
        Box(Modifier.size(36.dp).clip(CircleShape).press { shareUris(ctx, listOf(e.uri), mime) }, contentAlignment = Alignment.Center) {
            Icon(Ic.share, p.text2, size = 18.dp)
        }
    }
}
