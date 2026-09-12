package io.github.jamal_wia.kmptoolkit.uploader

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.logging.w
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Creates the Android [UploadTransport]: one WorkManager job per outbox item, keyed by item id with
 * [ExistingWorkPolicy.KEEP] — the idempotent-launch contract [UploadTransport.launch] requires,
 * for free.
 *
 * `classify` is remembered process-wide (the worker that actually calls it is instantiated
 * reflectively by WorkManager and cannot see this call's closure) — the last-created transport's
 * `classify` is the one every subsequent job uses, exactly like [UploaderEngineRegistry]. Create one
 * transport per process, from the same place you create your [UploaderEngine].
 *
 * @param context any `Context`; its application context is what gets retained.
 * @param config scheduling and HTTP timing knobs; see [UploadTransportConfig].
 * @param classify maps the transport's raw [UploadResult] onto [SettleResult]. Defaults to
 *   [defaultUploadClassification].
 * @param logger where a job that could not be enqueued, or a transport failure, is reported.
 */
public fun createWorkManagerUploadTransport(
    context: Context,
    config: UploadTransportConfig = UploadTransportConfig(),
    classify: (UploadResult) -> SettleResult = ::defaultUploadClassification,
    logger: Logger = NoopLogger,
): UploadTransport {
    UploadClassifierRegistry.register(classify)
    return WorkManagerUploadTransport(context.applicationContext, config, logger)
}

internal class WorkManagerUploadTransport(
    private val context: Context,
    private val config: UploadTransportConfig,
    private val logger: Logger,
) : UploadTransport {

    override val leaseMillis: Long = config.leaseMillis

    override fun launch(itemId: String, request: UploadRequest) {
        runCatching {
            val constraints: Constraints = Constraints.Builder()
                .setRequiredNetworkType(if (config.requiresNetwork) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED)
                .build()
            val work: OneTimeWorkRequest = OneTimeWorkRequestBuilder<UploaderUploadWorker>()
                .setConstraints(constraints)
                .setInputData(
                    request.toWorkData(
                        itemId = itemId,
                        engineWaitMillis = config.engineWaitMillis,
                        connectTimeoutMillis = config.connectTimeoutMillis,
                        readTimeoutMillis = config.readTimeoutMillis,
                    ),
                )
                // Paces WorkManager's own re-run of a killed/gateway-blocked job; delivery retries
                // are paced by the handler's RetryPolicy in the engine, not by this.
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, config.workBackoff.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                .addTag(config.resolveWorkTag(context.packageName))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                config.resolveUniqueWorkName(context.packageName, itemId),
                ExistingWorkPolicy.KEEP,
                work,
            )
        }.onFailure { failure -> logger.w(failure) { "Could not enqueue upload for item=$itemId" } }
    }

    override fun cancelAll() {
        runCatching { WorkManager.getInstance(context).cancelAllWorkByTag(config.resolveWorkTag(context.packageName)) }
            .onFailure { failure -> logger.w(failure) { "Could not cancel uploads" } }
    }
}

/**
 * The process-wide slot holding the last-registered `classify` function — the counterpart of
 * [UploaderEngineRegistry] for the one piece of [createWorkManagerUploadTransport] a
 * reflectively-instantiated [UploaderUploadWorker] cannot otherwise reach.
 */
internal object UploadClassifierRegistry {

    private val slot: MutableStateFlow<((UploadResult) -> SettleResult)?> = MutableStateFlow(null)

    fun register(classify: (UploadResult) -> SettleResult) {
        slot.value = classify
    }

    suspend fun await(timeoutMillis: Long): ((UploadResult) -> SettleResult)? =
        withTimeoutOrNull(timeoutMillis) { slot.filterNotNull().first() }
}

/**
 * One transport attempt: perform the request described in [getInputData], report the outcome to
 * whichever [UploaderEngine] is registered.
 *
 * WorkManager instantiates this reflectively; it needs no manifest entry of your own. It is public
 * only because WorkManager's default factory must be able to see the class.
 */
public class UploaderUploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val itemId: String = inputData.readItemId() ?: return Result.failure()
        val request: UploadRequest = inputData.toUploadRequestOrNull() ?: return Result.failure()
        val engineWaitMillis: Long = inputData.readEngineWaitMillis(UploadTransportConfig.DEFAULT_ENGINE_WAIT.inWholeMilliseconds)

        val engine: UploaderEngine = UploaderEngineRegistry.await(engineWaitMillis.asEngineWaitDuration())
            ?: return Result.retry()
        val classify: (UploadResult) -> SettleResult = UploadClassifierRegistry.await(engineWaitMillis)
            ?: return Result.retry()

        val connectTimeoutMillis: Int =
            inputData.readConnectTimeoutMillis(UploadTransportConfig.DEFAULT_CONNECT_TIMEOUT_MILLIS)
        val readTimeoutMillis: Int = inputData.readReadTimeoutMillis(UploadTransportConfig.DEFAULT_READ_TIMEOUT_MILLIS)

        val outcome: UploadResult = performUpload(request, connectTimeoutMillis, readTimeoutMillis)
        engine.settle(itemId, classify(outcome))
        return Result.success()
    }

    private fun performUpload(
        request: UploadRequest,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
    ): UploadResult {
        // Pre-flight the file parts so a vanished source reads as a transport failure the
        // handler's next attempt can react to, instead of an exception mid-stream.
        request.fields.filterIsInstance<UploadField.File>().forEach { part ->
            if (!File(part.path).canRead()) {
                return UploadResult.TransportFailure("source file missing/unreadable: ${part.path}")
            }
        }
        val boundary = "kmptoolkit-uploader-${UUID.randomUUID()}"
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
                requestMethod = request.method
                doOutput = true
                // Stream straight from disk — never buffer a multi-megabyte body in memory.
                setChunkedStreamingMode(0)
                connectTimeout = connectTimeoutMillis
                readTimeout = readTimeoutMillis
                request.headers.forEach { (name, value) -> setRequestProperty(name, value) }
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
            connection.outputStream.use { out -> writeMultipartBody(out, boundary, request) }
            val statusCode: Int = connection.responseCode
            UploadResult.Completed(statusCode)
        } catch (e: Exception) {
            // A server can reject-and-close before the chunked body finishes (size cap, proxy
            // 4xx) — the write then throws, but a status may still be readable. Prefer the real
            // status so classify() can react to a permanent rejection instead of retrying a
            // "transport failure" forever.
            val lateStatus: Int? = connection?.let { conn ->
                runCatching { conn.responseCode }.getOrNull()?.takeIf { it >= HTTP_BAD_REQUEST }
            }
            if (lateStatus != null) {
                UploadResult.Completed(lateStatus)
            } else {
                UploadResult.TransportFailure(e.message ?: e::class.simpleName)
            }
        } finally {
            connection?.disconnect()
        }
    }

    private fun writeMultipartBody(out: OutputStream, boundary: String, request: UploadRequest) {
        fun writeText(text: String) {
            out.write(text.encodeToByteArray())
        }
        request.fields.forEach { field ->
            writeText("--$boundary\r\n")
            when (field) {
                is UploadField.Text -> {
                    writeText("Content-Disposition: form-data; name=\"${field.name}\"\r\n\r\n")
                    writeText(field.value)
                }
                is UploadField.File -> {
                    writeText(
                        "Content-Disposition: form-data; name=\"${field.name}\"; " +
                            "filename=\"${field.fileName}\"\r\n",
                    )
                    writeText("Content-Type: ${field.contentType}\r\n\r\n")
                    File(field.path).inputStream().use { input -> input.copyTo(out) }
                }
            }
            writeText("\r\n")
        }
        writeText("--$boundary--\r\n")
    }

    private companion object {
        const val HTTP_BAD_REQUEST = 400
    }
}
