/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.ui.benchmark.input.pointer

import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.testutils.ComposeTestCase
import androidx.compose.testutils.benchmark.ComposeBenchmarkRule
import androidx.compose.testutils.doFramesUntilNoChangesPending
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose benchmarks for a single finger input (down/up and down/move/up) on an item using ONLY UI
 * module APIs (no Foundation calls which is what differentiates it from
 * [ComposeTapIntegrationBenchmark]). The benchmark uses pointerInput (ui) + awaitPointerEventScope
 * (ui) + awaitPointerEvent (ui) to track simple down/move/up inputs.
 *
 * The intent is to measure the speed of all parts necessary for a normal down, (from some
 * benchmarks, move,) and up starting from [MotionEvent]s getting dispatched to a particular view.
 * The test therefore includes hit testing and dispatch.
 *
 * The hierarchy is set up to look like: rootView -> Column -> Text (with click listener) -> Text
 * (with click listener) -> Text (with click listener) -> ...
 *
 * MotionEvents are dispatched to rootView as ACTION_DOWN and ACTION_UP (and for some benchmarks
 * ACTION_MOVE(s) are added). The validity of the test is verified inside awaitPointerEventScope { }
 * with com.google.common.truth.Truth.assertThat and by counting the events and later verifying that
 * they count is sufficiently high.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class ComposeOneFingerInputUIOnlyBenchmark {

    @get:Rule val benchmarkRule = ComposeBenchmarkRule()

    @Test
    fun clickOnLateItem() {
        // As items that are laid out last are hit tested first (so z order is respected), item
        // at 0 will be hit tested late.
        clickOnItem(0, "0", 0)
    }

    // This test requires less hit testing so changes to dispatch will be tracked more by this test.
    @Test
    fun clickOnEarlyItemFyi() {
        // As items that are laid out last are hit tested first (so z order is respected), item
        // at NumItems - 1 will be hit tested early.
        val lastItem = NumItems - 1
        clickOnItem(lastItem, "$lastItem", 0)
    }

    @Test
    fun clickWithMoveOnLateItem() {
        // As items that are laid out last are hit tested first (so z order is respected), item
        // at 0 will be hit tested late.
        clickOnItem(0, "0", 6)
    }

    // This test requires less hit testing so changes to dispatch will be tracked more by this test.
    @Test
    fun clickWithMoveOnEarlyItemFyi() {
        // As items that are laid out last are hit tested first (so z order is respected), item
        // at NumItems - 1 will be hit tested early.
        val lastItem = NumItems - 1
        clickOnItem(lastItem, "$lastItem", 6)
    }

    private fun clickOnItem(item: Int, expectedLabel: String, numberOfMoves: Int) {
        // half height of an item + top of the chosen item = middle of the chosen item
        val y = (ItemHeightPx / 2) + (item * ItemHeightPx)
        val xDown = 0f
        val xMoveInitial = xDown + MOVE_AMOUNT_PX

        benchmarkRule.runBenchmarkFor({ ComposeTapTestCase() }) {
            lateinit var case: ComposeTapTestCase
            lateinit var rootView: View

            benchmarkRule.runOnUiThread {
                doFramesUntilNoChangesPending()

                case = getTestCase()
                case.expectedLabel = expectedLabel

                rootView = getHostView()
            }

            // Simple Events
            val down =
                MotionEvent(
                    0,
                    MotionEvent.ACTION_DOWN,
                    1,
                    0,
                    arrayOf(PointerProperties(0)),
                    arrayOf(PointerCoords(xDown, y)),
                    rootView
                )

            val (time, x, moves) =
                createMoves(
                    initialX = xMoveInitial,
                    initialTime = 100,
                    y = y,
                    rootView = rootView,
                    numberOfMoveEvents = numberOfMoves
                )

            val up =
                MotionEvent(
                    time,
                    MotionEvent.ACTION_UP,
                    1,
                    0,
                    arrayOf(PointerProperties(0)),
                    arrayOf(PointerCoords(x, y)),
                    rootView
                )

            benchmarkRule.measureRepeatedOnUiThread {
                rootView.dispatchTouchEvent(down)
                case.expectedPressCount++
                assertThat(case.actualPressCount).isEqualTo(case.expectedPressCount)

                for (move in moves) {
                    rootView.dispatchTouchEvent(move)
                    case.expectedMoveCount++
                    assertThat(case.actualMoveCount).isEqualTo(case.expectedMoveCount)
                }
                // Double checks move count again (in case there weren't any moves).
                assertThat(case.actualMoveCount).isEqualTo(case.expectedMoveCount)

                rootView.dispatchTouchEvent(up)
                case.expectedReleaseCount++
                assertThat(case.actualReleaseCount).isEqualTo(case.expectedReleaseCount)

                assertThat(case.actualOtherEventCount).isEqualTo(case.expectedOtherEventCount)
            }
        }
    }

    private fun createMoves(
        initialX: Float,
        initialTime: Int,
        y: Float, // Same Y used for all moves
        rootView: View,
        numberOfMoveEvents: Int,
        timeDelta: Int = 100,
        moveDelta: Float = MOVE_AMOUNT_PX
    ): Triple<Int, Float, Array<MotionEvent>> {
        var time = initialTime
        var x = initialX

        val moveMotionEvents =
            Array(numberOfMoveEvents) {
                val move =
                    MotionEvent(
                        time,
                        MotionEvent.ACTION_MOVE,
                        1,
                        0,
                        arrayOf(PointerProperties(0)),
                        arrayOf(PointerCoords(x, y)),
                        rootView
                    )
                time += timeDelta
                x += moveDelta
                move
            }
        return Triple(time, x, moveMotionEvents)
    }

    private class ComposeTapTestCase : ComposeTestCase {
        private var itemHeightDp: Dp? = null // Is set to correct value during composition.
        var actualPressCount = 0
        var expectedPressCount = 0

        var actualMoveCount = 0
        var expectedMoveCount = 0

        var actualReleaseCount = 0
        var expectedReleaseCount = 0

        var actualOtherEventCount = 0
        var expectedOtherEventCount = 0

        lateinit var expectedLabel: String

        @Composable
        override fun Content() {
            with(LocalDensity.current) { itemHeightDp = ItemHeightPx.toDp() }

            EmailList(NumItems)
        }

        @Composable
        fun EmailList(count: Int) {
            Column { repeat(count) { i -> Email("$i") } }
        }

        @Composable
        fun Email(label: String) {
            BasicText(
                text = label,
                modifier =
                    Modifier.pointerInput(label) {
                            awaitPointerEventScope {
                                while (true) {
                                    assertThat(label).isEqualTo(expectedLabel)
                                    val event = awaitPointerEvent()

                                    when (event.type) {
                                        PointerEventType.Press -> {
                                            actualPressCount++
                                        }
                                        PointerEventType.Move -> {
                                            actualMoveCount++
                                        }
                                        PointerEventType.Release -> {
                                            actualReleaseCount++
                                        }
                                        else -> {
                                            actualOtherEventCount++
                                        }
                                    }
                                }
                            }
                        }
                        .fillMaxWidth()
                        .requiredHeight(itemHeightDp!!)
            )
        }
    }

    companion object {
        private const val MOVE_AMOUNT_PX = 10f
    }
}
