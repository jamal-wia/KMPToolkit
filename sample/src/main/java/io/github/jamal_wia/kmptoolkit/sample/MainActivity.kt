package io.github.jamal_wia.kmptoolkit.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.jamal_wia.kmptoolkit.files.AppRoot
import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.FileResult
import io.github.jamal_wia.kmptoolkit.files.Paths
import io.github.jamal_wia.kmptoolkit.files.createAppDirectories
import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import io.github.jamal_wia.kmptoolkit.files.getOrNull
import io.github.jamal_wia.kmptoolkit.files.withIo
import io.github.jamal_wia.kmptoolkit.haptics.HapticType
import io.github.jamal_wia.kmptoolkit.haptics.createHapticFeedback
import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.LogSink
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.createLoggerFactory
import io.github.jamal_wia.kmptoolkit.logging.i
import io.github.jamal_wia.kmptoolkit.sample.ui.theme.KMPToolkitTheme
import io.github.jamal_wia.kmptoolkit.storage.createKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.getStringOrNull
import kotlinx.coroutines.launch

/**
 * Exercises four KMPToolkit modules against whatever the build resolved them from — project
 * dependencies by default, or the artifacts published to Maven Central when run with
 * `-PusePublishedArtifacts`.
 *
 * It is deliberately not a showcase: every call here is one a consumer would actually make, so a
 * publication that is broken in a way the unit tests cannot see (a missing Android variant, a POM
 * that omits a transitive dependency) fails to compile or run here.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KMPToolkitTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SampleScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

private const val COUNTER_KEY: String = "sample.launch_counter"
private const val RECORD_DIRECTORY: String = "sample"
private const val RECORD_FILE: String = "record.txt"
private const val RECORD_MAX_BYTES: Long = 4096L

@Composable
private fun SampleScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // One sink that both writes to logcat's neighbour and feeds the on-screen list, so the sample
    // shows what the library actually emitted rather than a message composed for display.
    val lines = remember { mutableStateListOf<String>() }
    val logger: Logger = remember {
        val sink = LogSink { level, tag, message, _ -> lines.add("$level/$tag: $message") }
        createLoggerFactory(minLevel = LogLevel.DEBUG, sinks = listOf(sink)).logger("Sample")
    }

    val storage = remember { createKeyValueStorage(context) }
    val haptics = remember { createHapticFeedback(context) }
    val files: FileAccess = remember { createFileAccess() }
    val directories = remember { createAppDirectories(context, files) }

    val scope = rememberCoroutineScope()

    var counter: Int by remember { mutableStateOf(0) }
    LaunchedEffect(storage) {
        counter = storage.getStringOrNull(COUNTER_KEY)?.toIntOrNull() ?: 0
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("KMPToolkit sample", style = MaterialTheme.typography.headlineSmall)

        Text("Stored counter: $counter", style = MaterialTheme.typography.bodyLarge)

        Button(
            onClick = {
                counter += 1
                val value: Int = counter
                scope.launch {
                    val result = storage.put(COUNTER_KEY, value.toString())
                    logger.i { "stored counter=$value, result=$result" }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Increment and persist")
        }

        Button(
            onClick = {
                val text: String = "launch counter $counter"
                scope.launch {
                    // One durable record: written atomically (the old content or the whole new one survives a
                    // crash), read back with a size bound, every failure a typed value.
                    val outcome: String = files.withIo { _ ->
                        when (val directory: FileResult<String> = directories.directory(AppRoot.Files, RECORD_DIRECTORY)) {
                            is FileResult.Failure -> "directory failed: ${directory.error}"
                            is FileResult.Success -> {
                                val path: String = Paths.join(directory.value, RECORD_FILE)
                                val written: FileResult<Unit> = writeAtomically(path, text.encodeToByteArray())
                                val read: String? = readBytes(path, RECORD_MAX_BYTES).getOrNull()?.decodeToString()
                                "wrote=$written, read back='$read'"
                            }
                        }
                    }
                    logger.i { "record $outcome" }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Write and read back a record")
        }

        Button(
            onClick = {
                val outcome = haptics.perform(HapticType.MEDIUM)
                // The point of the typed result: a device with no motor, or an app that forgot the
                // VIBRATE permission, reports it here instead of failing silently.
                logger.i { "haptic outcome=$outcome" }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Perform haptic")
        }

        HorizontalDivider()
        Text("Log output", style = MaterialTheme.typography.titleMedium)
        if (lines.isEmpty()) {
            Text("Nothing logged yet.", style = MaterialTheme.typography.bodySmall)
        } else {
            lines.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
