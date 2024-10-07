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
package com.example.tracing.demo

import android.app.Activity
import android.os.Bundle
import android.os.Trace
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.android.app.tracing.coroutines.createCoroutineTracingContext
import com.example.tracing.demo.experiments.Experiment
import com.example.tracing.demo.experiments.TRACK_NAME
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : Activity() {

    private val allExperiments by lazy {
        (applicationContext as MainApplication).appComponent.getAllExperiments()
    }

    val mainScope: CoroutineScope =
        CoroutineScope(
            Dispatchers.Main +
                createCoroutineTracingContext("test-scope", walkStackForDefaultNames = true)
        )

    private var logContainer: ScrollView? = null
    private var loggerView: TextView? = null

    private fun <T : Experiment> connectButtonsForExperiment(demo: T, view: ViewGroup) {
        val className = demo::class.simpleName
        view.findViewById<TextView>(R.id.description).text =
            baseContext.getString(R.string.run_experiment_button_text, className, demo.description)
        val currentState = view.findViewById<TextView>(R.id.current_state)

        val launchedJobs = mutableListOf<Job>()

        view.findViewById<Button>(R.id.start_button).setOnClickListener {
            val cookie = Random.nextInt()
            Trace.asyncTraceForTrackBegin(
                Trace.TRACE_TAG_APP,
                TRACK_NAME,
                "Running: $className",
                cookie,
            )

            val job = mainScope.launch { demo.start() }
            job.invokeOnCompletion { cause ->
                Trace.asyncTraceForTrackEnd(Trace.TRACE_TAG_APP, TRACK_NAME, cookie)
                val message =
                    when (cause) {
                        null -> "completed normally"
                        is CancellationException -> "cancelled normally"
                        else -> "failed"
                    }
                mainExecutor.execute {
                    currentState.text = message
                    appendLine("$className $message")
                }
            }

            launchedJobs.add(job)

            currentState.text = "started"
            appendLine("$className started")
        }

        view.findViewById<Button>(R.id.cancel_button).setOnClickListener {
            var activeJobs = 0
            launchedJobs.forEach {
                if (it.isActive) activeJobs++
                it.cancel()
            }
            appendLine(if (activeJobs == 0) "Nothing to cancel." else "Cancelled $activeJobs jobs.")
            launchedJobs.clear()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        logContainer = requireViewById(R.id.log_container)
        loggerView = requireViewById(R.id.logger_view)
        val experimentList = requireViewById<LinearLayout>(R.id.experiment_list)
        val inflater = LayoutInflater.from(baseContext)
        allExperiments.forEach {
            val experimentButtons =
                inflater.inflate(R.layout.experiment_buttons, experimentList, false) as ViewGroup
            connectButtonsForExperiment(it.value.get(), experimentButtons)
            experimentList.addView(experimentButtons)
        }
    }

    private fun appendLine(message: String) {
        loggerView?.append("$message\n")
        logContainer?.fullScroll(View.FOCUS_DOWN)
    }
}
