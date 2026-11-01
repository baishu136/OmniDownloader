package com.omni.downloader.ui.design

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class RetainedTabHostTest {
    @get:Rule val compose = createComposeRule()

    @Test fun hiddenPageStopsCollectionAndMeasurementWhileInputsAndScrollSurvive() {
        var selected by mutableIntStateOf(0)
        val collectors = AtomicInteger()
        val updates = MutableStateFlow(1)
        val measures = IntArray(3)
        val tokens = arrayOfNulls<Any>(3)
        compose.setContent {
            MaterialTheme {
                RetainedTabHost(selected, 3, Modifier.fillMaxSize()) { tab ->
                    tokens[tab] = remember { Any() }
                    val source = remember { flow {
                        collectors.incrementAndGet()
                        try { updates.collect { emit(it) } }
                        finally { collectors.decrementAndGet() }
                    } }
                    val number by source.collectAsStateWithLifecycle(0)
                    var input by rememberSaveable { mutableStateOf("") }
                    val scroll = rememberLazyListState()
                    Column(Modifier.testTag("page" + tab).layout { measurable, constraints ->
                        measures[tab]++
                        val p = measurable.measure(constraints)
                        layout(p.width, p.height) { p.place(0, 0) }
                    }) {
                        Text(number.toString())
                        BasicTextField(input, { input = it }, Modifier.testTag("input" + tab))
                        LazyColumn(state = scroll, modifier = Modifier.testTag("list" + tab)) {
                            items((0 until 200).toList(), key = { it }) { Text("row" + it, Modifier.height(48.dp)) }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("input0").performTextInput("retained")
        compose.onNodeWithTag("list0").performScrollToIndex(100)
        val firstToken = tokens[0]
        compose.runOnIdle { selected = 1 }
        compose.waitForIdle()
        compose.onNodeWithTag("page0").assertIsNotDisplayed()
        val hiddenMeasures = measures[0]
        repeat(12) { n ->
            compose.runOnIdle { updates.value = n + 2 }
            compose.waitForIdle()
        }
        compose.runOnIdle { assertEquals("Hidden page must not remeasure for progress emissions", hiddenMeasures, measures[0]) }
        compose.runOnIdle { assertEquals(1, collectors.get()) }
        repeat(6) {
            compose.runOnIdle { selected = if (it % 2 == 0) 2 else 1 }
            compose.waitForIdle()
        }
        compose.runOnIdle {
            assertEquals(1, collectors.get())
            selected = 0
        }
        compose.onNodeWithTag("input0").assertTextContains("retained")
        compose.onNodeWithText("row100").assertIsDisplayed()
        compose.runOnIdle { assertSame(firstToken, tokens[0]); assertEquals(1, collectors.get()) }
    }
    @Test fun rapidTabRetargetRestoresContentAndRetainsIdentity() {
        var selected by mutableIntStateOf(0)
        val tokens = arrayOfNulls<Any>(3)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RetainedTabHost(selected, 3, Modifier.fillMaxSize()) { tab ->
                tokens[tab] = remember { Any() }
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()
                    .testTag("rapid" + tab)
                    .then(Modifier.background(if (tab == 0) androidx.compose.ui.graphics.Color.Red else androidx.compose.ui.graphics.Color.Blue)))
            }
        }
        val original = tokens[0]
        compose.runOnUiThread { selected = 1 }
        compose.mainClock.advanceTimeBy(32)
        compose.runOnUiThread { selected = 2 }
        compose.mainClock.advanceTimeBy(32)
        compose.runOnUiThread { selected = 0 }
        compose.mainClock.advanceTimeBy(500)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("rapid0").assertIsDisplayed()
        compose.runOnIdle { assertSame(original, tokens[0]) }
        val bitmap = compose.onRoot().captureToImage().toPixelMap()
        assertEquals(androidx.compose.ui.graphics.Color.Red, bitmap[bitmap.width / 2, bitmap.height / 2])
    }

}
