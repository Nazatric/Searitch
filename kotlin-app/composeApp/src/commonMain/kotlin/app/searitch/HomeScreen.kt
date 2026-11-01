@file:OptIn(ExperimentalLayoutApi::class)

package app.searitch

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

/** Minimal landing screen: a cream wordmark pressed into the hide, and one search field. */
@Composable
fun HomeScreen(
    onSearch: (String, Filters) -> Unit,
    onOpenSettings: () -> Unit,
    settingsOpen: Boolean,
) {
    val settings = LocalSettings.current.value
    val haptics = rememberHaptics()
    var input by remember { mutableStateOf("") }
    var entered by remember { mutableStateOf(false) }
    var recents by remember { mutableStateOf(Recents.list()) }
    LaunchedEffect(Unit) { entered = true }
    val appear by animateFloatAsState(
        if (entered || !settings.motion) 1f else 0f,
        spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow),
        label = "homeAppear",
    )
    val pal = LocalPalette.current

    fun go(text: String = input) {
        val q = text.trim()
        if (q.isEmpty()) return
        Recents.add(q)
        recents = Recents.list()
        onSearch(q, settings.baseFilters())
    }

    Box(Modifier.fillMaxSize().leatherSurface(tall = true)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(
                    top = if (settings.homeBar == HomeBarPosition.TOP) 76.dp else 32.dp,
                    bottom = 32.dp,
                )
                .graphicsLayer { alpha = appear; translationY = (1f - appear) * 40f },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = when (settings.homeBar) {
                HomeBarPosition.TOP -> Arrangement.Top
                HomeBarPosition.MIDDLE -> Arrangement.Center
                HomeBarPosition.BOTTOM -> Arrangement.Bottom
            },
        ) {
            Text(
                "Searitch",
                color = Ink.onLeather,
                fontFamily = cinzel(),
                fontWeight = FontWeight.Bold,
                fontSize = 40.sp,
                letterSpacing = 4.sp,
            )
            Text(
                "SEARCH ANYTHING",
                color = Ink.onLeatherSoft,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 4.sp,
                modifier = Modifier.padding(top = 12.dp, bottom = 40.dp),
            )

            // the field: debossed paper, with a brass search button set into it
            Row(
                modifier = Modifier
                    .sharedField()
                    .widthIn(max = 460.dp)
                    .fillMaxWidth()
                    .height(56.dp)
                    .paperField()
                    .padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Ico.Search, Ink.faint, Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Ink.strong, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                    cursorBrush = SolidColor(pal.brassDark),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { go() }),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (input.isEmpty()) {
                                Text(
                                    "Search anything…",
                                    color = Ink.faint,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            inner()
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(brassBrush(), CircleShape)
                        .border(1.dp, pal.brassDark, CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            haptics.tap()
                            go()
                        },
                    contentAlignment = Alignment.Center,
                ) { Icon(Ico.Search, pal.brassText, Modifier.size(18.dp)) }
            }

            if (recents.isNotEmpty()) {
                Column(
                    Modifier.padding(top = 26.dp).widthIn(max = 460.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "RECENT",
                            style = TextStyle(
                                color = Ink.onLeatherSoft,
                                fontFamily = cinzel(),
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                letterSpacing = 2.5.sp,
                            ),
                        )
                        Text(
                            "clear",
                            color = Ink.onLeatherSoft.copy(alpha = 0.75f),
                            fontSize = 11.sp,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    Recents.clear()
                                    recents = emptyList()
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        recents.forEach { q ->
                            SearitchChip(q, selected = false) {
                                input = q
                                go(q)
                            }
                        }
                    }
                }
            }
        }

        /*
         * Declared *after* the full-size column and lifted with zIndex: a
         * fillMaxSize scroll container drawn later swallows the taps, which is
         * exactly why the gear felt dead on this screen.
         */
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .zIndex(2f)
                .statusBarsPadding()
                .padding(end = 14.dp, top = 10.dp),
        ) {
            GearButton(onClick = onOpenSettings, open = settingsOpen)
        }
    }
}
