package io.github.jamal_wia.kmptoolkit.uploader

import androidx.work.Data
import androidx.work.workDataOf

/**
 * Encodes [UploadRequest] into WorkManager's own primitive [Data] — no serialization library, so
 * this module stays dependency-free beyond `work-runtime-ktx`, which the wake scheduler already
 * needs.
 *
 * Every field is one parallel array, one entry per multipart field: [KEY_FIELD_KIND] tells
 * [Data.toUploadRequestOrNull] which of the other four to read for that index. [Data] is capped at
 * ~10 KB serialized — comfortably enough for a URL, a handful of headers, and per-field metadata,
 * since file *contents* stream from [UploadField.File.path] and never touch this encoding.
 */
internal fun UploadRequest.toWorkData(
    itemId: String,
    engineWaitMillis: Long,
    connectTimeoutMillis: Int,
    readTimeoutMillis: Int,
): Data {
    val fieldKinds: Array<String> = fields.map { field ->
        when (field) {
            is UploadField.Text -> FIELD_KIND_TEXT
            is UploadField.File -> FIELD_KIND_FILE
        }
    }.toTypedArray()
    val fieldNames: Array<String> = fields.map { field ->
        when (field) {
            is UploadField.Text -> field.name
            is UploadField.File -> field.name
        }
    }.toTypedArray()
    // The text field's value, or the file field's display name — never both, so one array does.
    val fieldSecondary: Array<String> = fields.map { field ->
        when (field) {
            is UploadField.Text -> field.value
            is UploadField.File -> field.fileName
        }
    }.toTypedArray()
    val fieldContentTypes: Array<String> = fields.map { field ->
        if (field is UploadField.File) field.contentType else ""
    }.toTypedArray()
    val fieldPaths: Array<String> = fields.map { field ->
        if (field is UploadField.File) field.path else ""
    }.toTypedArray()

    return workDataOf(
        KEY_ITEM_ID to itemId,
        KEY_ENGINE_WAIT_MILLIS to engineWaitMillis,
        KEY_CONNECT_TIMEOUT_MILLIS to connectTimeoutMillis,
        KEY_READ_TIMEOUT_MILLIS to readTimeoutMillis,
        KEY_URL to url,
        KEY_METHOD to method,
        KEY_HEADER_KEYS to headers.keys.toTypedArray(),
        KEY_HEADER_VALUES to headers.values.toTypedArray(),
        KEY_FIELD_KIND to fieldKinds,
        KEY_FIELD_NAME to fieldNames,
        KEY_FIELD_SECONDARY to fieldSecondary,
        KEY_FIELD_CONTENT_TYPE to fieldContentTypes,
        KEY_FIELD_PATH to fieldPaths,
    )
}

/** The item id encoded by [toWorkData], or `null` if this [Data] was not built by it. */
internal fun Data.readItemId(): String? = getString(KEY_ITEM_ID)

/** The engine-wait duration encoded by [toWorkData], in milliseconds, or [default] if absent. */
internal fun Data.readEngineWaitMillis(default: Long): Long = getLong(KEY_ENGINE_WAIT_MILLIS, default)

/** The connect timeout encoded by [toWorkData], in milliseconds, or [default] if absent. */
internal fun Data.readConnectTimeoutMillis(default: Int): Int = getInt(KEY_CONNECT_TIMEOUT_MILLIS, default)

/** The read timeout encoded by [toWorkData], in milliseconds, or [default] if absent. */
internal fun Data.readReadTimeoutMillis(default: Int): Int = getInt(KEY_READ_TIMEOUT_MILLIS, default)

/** Reconstructs the [UploadRequest] encoded by [toWorkData], or `null` if a required field is missing. */
internal fun Data.toUploadRequestOrNull(): UploadRequest? {
    val url: String = getString(KEY_URL) ?: return null
    val method: String = getString(KEY_METHOD) ?: return null
    val headerKeys: Array<String> = getStringArray(KEY_HEADER_KEYS) ?: return null
    val headerValues: Array<String> = getStringArray(KEY_HEADER_VALUES) ?: return null
    if (headerKeys.size != headerValues.size) return null
    val fieldKinds: Array<String> = getStringArray(KEY_FIELD_KIND) ?: return null
    val fieldNames: Array<String> = getStringArray(KEY_FIELD_NAME) ?: return null
    val fieldSecondary: Array<String> = getStringArray(KEY_FIELD_SECONDARY) ?: return null
    val fieldContentTypes: Array<String> = getStringArray(KEY_FIELD_CONTENT_TYPE) ?: return null
    val fieldPaths: Array<String> = getStringArray(KEY_FIELD_PATH) ?: return null
    if (
        setOf(fieldNames.size, fieldSecondary.size, fieldContentTypes.size, fieldPaths.size) != setOf(fieldKinds.size)
    ) {
        return null
    }

    val fields: List<UploadField> = fieldKinds.indices.map { index ->
        when (fieldKinds[index]) {
            FIELD_KIND_TEXT -> UploadField.Text(name = fieldNames[index], value = fieldSecondary[index])
            FIELD_KIND_FILE -> UploadField.File(
                name = fieldNames[index],
                fileName = fieldSecondary[index],
                contentType = fieldContentTypes[index],
                path = fieldPaths[index],
            )
            else -> return null
        }
    }

    return UploadRequest(
        url = url,
        method = method,
        headers = headerKeys.zip(headerValues).toMap(),
        fields = fields,
    )
}

private const val KEY_ITEM_ID = "kmptoolkit_uploader_item_id"
private const val KEY_ENGINE_WAIT_MILLIS = "kmptoolkit_uploader_engine_wait_millis"
private const val KEY_CONNECT_TIMEOUT_MILLIS = "kmptoolkit_uploader_connect_timeout_millis"
private const val KEY_READ_TIMEOUT_MILLIS = "kmptoolkit_uploader_read_timeout_millis"
private const val KEY_URL = "kmptoolkit_uploader_url"
private const val KEY_METHOD = "kmptoolkit_uploader_method"
private const val KEY_HEADER_KEYS = "kmptoolkit_uploader_header_keys"
private const val KEY_HEADER_VALUES = "kmptoolkit_uploader_header_values"
private const val KEY_FIELD_KIND = "kmptoolkit_uploader_field_kind"
private const val KEY_FIELD_NAME = "kmptoolkit_uploader_field_name"
private const val KEY_FIELD_SECONDARY = "kmptoolkit_uploader_field_secondary"
private const val KEY_FIELD_CONTENT_TYPE = "kmptoolkit_uploader_field_content_type"
private const val KEY_FIELD_PATH = "kmptoolkit_uploader_field_path"
private const val FIELD_KIND_TEXT = "text"
private const val FIELD_KIND_FILE = "file"
