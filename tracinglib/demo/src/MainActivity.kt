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

import android.os.Bundle
import android.os.Trace
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons.Filled
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.android.app.tracing.coroutines.createCoroutineTracingContext
import com.example.tracing.demo.experiments.AsyncExperiment
import com.example.tracing.demo.experiments.BlockingExperiment
import com.example.tracing.demo.experiments.Experiment
import com.example.tracing.demo.experiments.TRACK_NAME
import com.example.tracing.demo.experiments.startThreadWithLooper
import com.example.tracing.demo.ui.theme.BasicsCodelabTheme
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.launch

private val demoMonitorThread by lazy { startThreadWithLooper("demo-monitor-thread") }

private val demoMonitorScope: CoroutineScope =
    CoroutineScope(
        demoMonitorThread.threadHandler.asCoroutineDispatcher() +
            createCoroutineTracingContext(
                "demo-monitor-scope",
                walkStackForDefaultNames = true,
                countContinuations = true,
            )
    )

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val experimentByClass =
            (applicationContext as MainApplication).appComponent.getAllExperiments()
        enableEdgeToEdge()
        setContent {
            BasicsCodelabTheme {
                DemoApp(
                    modifier = Modifier.safeDrawingPadding(),
                    allExperiments = experimentByClass.values.stream().map { it.get() }.toList(),
                )
            }
        }
    }
}

@Composable
fun DemoApp(modifier: Modifier = Modifier, allExperiments: List<Experiment>) {
    Surface(modifier) { ExperimentList(allExperiments = allExperiments) }
}

@Composable
private fun ExperimentList(modifier: Modifier = Modifier, allExperiments: List<Experiment>) {
    LazyColumn(modifier = modifier.padding(vertical = 4.dp)) {
        items(items = allExperiments) { experiment -> ExperimentCard(experiment = experiment) }
    }
}

@Composable
private fun ExperimentCard(experiment: Experiment, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
        modifier = modifier.padding(vertical = 4.dp, horizontal = 8.dp),
    ) {
        ExperimentContent(experiment)
    }
}

@Composable
private fun ExperimentContent(experiment: Experiment) {
    var expanded by remember { mutableStateOf(false) }
    var currentlyRunning by remember { mutableStateOf(false) }
    val statusMessages = remember { mutableStateListOf<String>() }
    var currentJob by remember { mutableStateOf<Job?>(null) }
    Row(
        modifier =
            Modifier.padding(12.dp)
                .animateContentSize(
                    animationSpec =
                        spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMedium,
                        )
                )
    ) {
        Column(modifier = Modifier.weight(1f).padding(12.dp)) {
            Text(
                text = experiment.javaClass.simpleName,
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = experiment.description,
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
            )
            if (expanded) {
                Text(
                    text = statusMessages.joinToString(separator = "\n") { it },
                    style =
                        MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
        IconButton(
            onClick = {
                currentlyRunning = !currentlyRunning
                if (currentlyRunning) {
                    val cookie = Random.nextInt()
                    Trace.asyncTraceForTrackBegin(
                        Trace.TRACE_TAG_APP,
                        TRACK_NAME,
                        "Running: ${experiment.javaClass.simpleName}",
                        cookie,
                    )
                    statusMessages += "Started"
                    currentJob =
                        demoMonitorScope.launch {
                            when (experiment) {
                                is BlockingExperiment -> experiment.start()
                                is AsyncExperiment -> experiment.start()
                            }
                        }
                    currentJob!!.invokeOnCompletion { cause ->
                        currentlyRunning = false
                        Trace.asyncTraceForTrackEnd(Trace.TRACE_TAG_APP, TRACK_NAME, cookie)
                        statusMessages +=
                            when (cause) {
                                null -> "completed normally"
                                is CancellationException -> "cancelled normally"
                                else -> "failed"
                            }
                    }
                    expanded = true
                } else {
                    currentJob?.cancel()
                    currentJob = null
                }
            }
        ) {
            Icon(
                imageVector = if (currentlyRunning) Filled.StopCircle else Filled.PlayCircleOutline,
                contentDescription = stringResource(R.string.run_experiment),
            )
        }
        IconButton(
            onClick = {
                expanded = !expanded
                if (!expanded) {
                    statusMessages.clear()
                }
            }
        ) {
            Icon(
                imageVector = if (expanded) Filled.ExpandLess else Filled.ExpandMore,
                contentDescription =
                    if (expanded) stringResource(R.string.show_less)
                    else stringResource(R.string.show_more),
            )
        }
    }
}
