/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.test.tracing.coroutines

import android.os.Looper
import android.platform.test.flag.junit.SetFlagsRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.app.tracing.coroutines.CoroutineTraceName
import com.android.test.tracing.coroutines.util.FakeTraceState
import com.android.test.tracing.coroutines.util.FakeTraceState.getOpenTraceSectionsOnCurrentThread
import com.android.test.tracing.coroutines.util.ShadowTrace
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

class InvalidTraceStateException(message: String) : Exception(message)

@RunWith(AndroidJUnit4::class)
@Config(shadows = [ShadowTrace::class])
open class TestBase {

    companion object {
        @JvmField
        @ClassRule
        val setFlagsClassRule: SetFlagsRule.ClassRule = SetFlagsRule.ClassRule()
    }

    @JvmField @Rule val setFlagsRule = SetFlagsRule()

    private val eventCounter = AtomicInteger(0)
    private val finalEvent = AtomicInteger(INVALID_EVENT)
    private var expectedExceptions = false
    private lateinit var allExceptions: MutableList<Throwable>
    private lateinit var shadowLooper: ShadowLooper
    private lateinit var mainTraceScope: CoroutineScope

    open val extraCoroutineContext: CoroutineContext
        get() = EmptyCoroutineContext

    @Before
    fun setup() {
        FakeTraceState.isTracingEnabled = true
        eventCounter.set(0)
        allExceptions = mutableListOf()
        shadowLooper = shadowOf(Looper.getMainLooper())
        mainTraceScope = CoroutineScope(Dispatchers.Main + extraCoroutineContext)
    }

    @After
    fun tearDown() {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        allExceptions.forEach { it.printStackTrace(pw) }
        assertTrue("Test failed due to incorrect trace sections\n$sw", allExceptions.isEmpty())

        val lastEvent = eventCounter.get()
        assertTrue(
            "`finish()` was never called. Last seen event was #$lastEvent",
            lastEvent == FINAL_EVENT || lastEvent == 0 || expectedExceptions,
        )
    }

    protected fun runTest(
        expectedException: ((Throwable) -> Boolean)? = null,
        block: suspend CoroutineScope.() -> Unit,
    ) {
        var foundExpectedException = false
        if (expectedException != null) expectedExceptions = true
        mainTraceScope.launch(
            block = block,
            context =
                CoroutineExceptionHandler { _, e ->
                    if (e is CancellationException) return@CoroutineExceptionHandler // ignore
                    if (expectedException != null && expectedException(e)) {
                        foundExpectedException = true
                        return@CoroutineExceptionHandler // ignore
                    }
                    allExceptions.add(e)
                },
        )

        for (n in 0..1000) {
            shadowLooper.idleFor(1, MILLISECONDS)
        }

        val names = mutableListOf<String?>()
        var numChildren = 0
        mainTraceScope.coroutineContext[Job]?.children?.forEach { it ->
            names.add(it[CoroutineTraceName]?.name)
            numChildren++
        }

        val allNames =
            names.joinToString(prefix = "{ ", separator = ", ", postfix = " }") {
                it?.let { "\"$it\" " } ?: "unnamed"
            }
        assertEquals(
            "The main test scope still has $numChildren running jobs: $allNames.",
            0,
            numChildren,
        )
        if (expectedExceptions) {
            assertTrue("Expected exceptions, but none were thrown", foundExpectedException)
        }
    }

    private fun logInvalidTraceState(message: String) {
        allExceptions.add(InvalidTraceStateException(message))
    }

    /**
     * Same as [expect], but also call [delay] for 1ms, calling [expect] before and after the
     * suspension point.
     */
    protected suspend fun expectD(vararg expectedOpenTraceSections: String) {
        expect(*expectedOpenTraceSections)
        delay(1)
        expect(*expectedOpenTraceSections)
    }

    /**
     * Same as [expect], but also call [delay] for 1ms, calling [expect] before and after the
     * suspension point.
     */
    protected suspend fun expectD(expectedEvent: Int, vararg expectedOpenTraceSections: String) {
        expect(expectedEvent, *expectedOpenTraceSections)
        delay(1)
        expect(*expectedOpenTraceSections)
    }

    protected fun expectEndsWith(vararg expectedOpenTraceSections: String) {
        // Inspect trace output to the fake used for recording android.os.Trace API calls:
        val actualSections = getOpenTraceSectionsOnCurrentThread()
        if (expectedOpenTraceSections.size <= actualSections.size) {
            val lastSections =
                actualSections.takeLast(expectedOpenTraceSections.size).toTypedArray()
            assertTraceSectionsEquals(expectedOpenTraceSections, null, lastSections, null)
        } else {
            logInvalidTraceState(
                "Invalid length: expected size (${expectedOpenTraceSections.size}) <= actual size (${actualSections.size})"
            )
        }
    }

    protected fun expectEvent(expectedEvent: Collection<Int>): Int {
        val previousEvent = eventCounter.getAndAdd(1)
        val currentEvent = previousEvent + 1
        if (!expectedEvent.contains(currentEvent)) {
            logInvalidTraceState(
                if (previousEvent == FINAL_EVENT) {
                    "Expected event ${expectedEvent.prettyPrintList()}, but finish() was already called"
                } else {
                    "Expected event ${expectedEvent.prettyPrintList()}," +
                        " but the event counter is currently at #$currentEvent"
                }
            )
        }
        return currentEvent
    }

    internal fun expect(vararg expectedOpenTraceSections: String) {
        expect(null, *expectedOpenTraceSections)
    }

    internal fun expect(expectedEvent: Int, vararg expectedOpenTraceSections: String) {
        expect(listOf(expectedEvent), *expectedOpenTraceSections)
    }

    /**
     * Checks the currently active trace sections on the current thread, and optionally checks the
     * order of operations if [expectedEvent] is not null.
     */
    internal fun expect(possibleEventPos: List<Int>?, vararg expectedOpenTraceSections: String) {
        var currentEvent: Int? = null
        if (possibleEventPos != null) {
            currentEvent = expectEvent(possibleEventPos)
        }
        val actualOpenSections = getOpenTraceSectionsOnCurrentThread()
        assertTraceSectionsEquals(
            expectedOpenTraceSections,
            possibleEventPos,
            actualOpenSections,
            currentEvent,
        )
    }

    private fun assertTraceSectionsEquals(
        expectedOpenTraceSections: Array<out String>,
        expectedEvent: List<Int>?,
        actualOpenSections: Array<String>,
        actualEvent: Int?,
    ) {
        val expectedSize = expectedOpenTraceSections.size
        val actualSize = actualOpenSections.size
        if (expectedSize != actualSize) {
            logInvalidTraceState(
                createFailureMessage(
                    expectedOpenTraceSections,
                    expectedEvent,
                    actualOpenSections,
                    actualEvent,
                    "Size mismatch, expected size $expectedSize but was size $actualSize",
                )
            )
        } else {
            expectedOpenTraceSections.forEachIndexed { n, expectedTrace ->
                val actualTrace = actualOpenSections[n]
                val expected = expectedTrace.substringBefore(";")
                val actual = actualTrace.substringBefore(";")
                if (expected != actual) {
                    logInvalidTraceState(
                        createFailureMessage(
                            expectedOpenTraceSections,
                            expectedEvent,
                            actualOpenSections,
                            actualEvent,
                            "Differed at index #$n, expected \"$expected\" but was \"$actual\"",
                        )
                    )
                    return@forEachIndexed
                }
            }
        }
    }

    private fun createFailureMessage(
        expectedOpenTraceSections: Array<out String>,
        expectedEventNumber: List<Int>?,
        actualOpenSections: Array<String>,
        actualEventNumber: Int?,
        extraMessage: String,
    ): String {
        val locationMarker =
            if (expectedEventNumber == null || actualEventNumber == null) ""
            else if (expectedEventNumber.contains(actualEventNumber))
                " at event #$actualEventNumber"
            else
                ", expected event ${expectedEventNumber.prettyPrintList()}, actual event #$actualEventNumber"
        return """
                Incorrect trace$locationMarker. $extraMessage
                  Expected : {${expectedOpenTraceSections.prettyPrintList()}}
                  Actual   : {${actualOpenSections.prettyPrintList()}}
                """
            .trimIndent()
    }

    /** Same as [expect], except that no more [expect] statements can be called after it. */
    protected fun finish(expectedEvent: Int, vararg expectedOpenTraceSections: String) {
        finalEvent.compareAndSet(INVALID_EVENT, expectedEvent)
        val previousEvent = eventCounter.getAndSet(FINAL_EVENT)
        val currentEvent = previousEvent + 1
        if (expectedEvent != currentEvent) {
            logInvalidTraceState(
                "Expected to finish with event #$expectedEvent, but " +
                    if (previousEvent == FINAL_EVENT)
                        "finish() was already called with event #${finalEvent.get()}"
                    else "the event counter is currently at #$currentEvent"
            )
        }
        assertTraceSectionsEquals(
            expectedOpenTraceSections,
            listOf(expectedEvent),
            getOpenTraceSectionsOnCurrentThread(),
            currentEvent,
        )
    }
}

private const val INVALID_EVENT = -1

private const val FINAL_EVENT = Int.MIN_VALUE

private fun Collection<Int>.prettyPrintList(): String {
    return if (isEmpty()) ""
    else if (size == 1) "#${iterator().next()}"
    else {
        "{${
            toList().joinToString(
                separator = ", #",
                prefix = "#",
                postfix = "",
            ) { it.toString() }
        }}"
    }
}

private fun Array<out String>.prettyPrintList(): String {
    return if (isEmpty()) ""
    else
        toList().joinToString(separator = "\", \"", prefix = "\"", postfix = "\"") {
            it.substringBefore(";")
        }
}
