# Getting started

Five minutes to a queue that survives a restart.

## 1. Add the dependency

```kotlin
// build.gradle.kts (shared module)
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.jamal-wia:kmptoolkit-uploader")
        }
        commonTest.dependencies {
            implementation("io.github.jamal-wia:kmptoolkit-uploader-testing")
        }
    }
}
```

With the BOM applied, no version is needed — see the root [`README`](../../README.md).

## 2. Pick a store

The engine persists through an `UploaderStore` you supply. For a first run, take the in-memory one
from the testing artifact — it is a complete implementation, just not a durable one:

```kotlin
val store: UploaderStore = InMemoryUploaderStore()
```

For production, use `kmptoolkit-uploader-sqldelight`, or write your own against
[`07-custom-store.md`](07-custom-store.md). Everything below is identical either way.

## 3. Write a handler

One per kind of effect. It owns the payload format and the delivery itself:

```kotlin
@Serializable
data class SendMessage(val conversationId: String, val body: String)

class SendMessageHandler(private val api: ChatApi) : UploaderHandler<SendMessage> {

    override val type: String = "chat.send_message"

    override fun encodePayload(payload: SendMessage): String = Json.encodeToString(payload)

    override fun decodePayload(raw: String): SendMessage = Json.decodeFromString(raw)

    // Messages in one conversation deliver in order; different conversations are independent.
    override fun orderingKey(payload: SendMessage): String = payload.conversationId

    override suspend fun execute(context: AttemptContext, payload: SendMessage): AttemptResult =
        when (val response = api.send(payload, idempotencyKey = context.id)) {
            is ApiResponse.Ok -> AttemptResult.Success
            is ApiResponse.ServerError -> AttemptResult.Retry(response.cause)
            is ApiResponse.Rejected -> AttemptResult.Park(response.reason)
        }
}
```

Three rules are worth internalizing now, because they are what makes the queue trustworthy:

- **`execute` may run more than once for the same effect.** Pass `context.id` as an idempotency key.
- **Return a result; do not throw.** A thrown exception is treated as `Retry`, which is a safe
  default but tells the engine nothing.
- **Decide staleness here.** If the message was deleted while it sat in the queue, return
  `AttemptResult.Drop` rather than sending it.

## 4. Build the engine and start it

Once, at application startup:

```kotlin
val uploader: UploaderEngine = createUploaderEngine(
    store = store,
    handlers = listOf(sendMessageHandler),
    scope = applicationScope,
)
uploader.start()
```

`start()` immediately drains anything the previous process left behind, so a message queued
yesterday goes out on today's launch.

Hand the narrow `Uploader` interface to your features; keep the `UploaderEngine` reference where the
lifecycle lives.

## 5. Enqueue

```kotlin
class MessageRepository(private val uploader: Uploader) {

    suspend fun send(conversationId: String, body: String) {
        uploader.enqueue(
            handler = sendMessageHandler,
            payload = SendMessage(conversationId, body),
            uniqueKey = "message-$localId",   // a second tap does not queue a second send
            tag = "session-$userId",          // wipe everything of this user's on logout
        )
    }
}
```

`enqueue` suspends until the effect is persisted. When it returns, the effect will be delivered —
now if possible, on the next launch if not.

## 6. Show what is still owed

```kotlin
val pending: Flow<List<UploaderItem>> = uploader.observe("chat.send_message")

// e.g. a per-message state for the UI
val stillSending: Flow<Set<String>> = pending.map { items ->
    items.filter { it.state != UploaderItemState.PARKED }
        .mapNotNull { it.uniqueKey }
        .toSet()
}
```

## 7. Optional — wake the app after it is killed

Android:

```kotlin
val uploader: UploaderEngine = createUploaderEngine(
    store = store,
    handlers = handlers,
    scope = applicationScope,
    wakeScheduler = createWorkManagerWakeScheduler(context),
).also { it.start() }

// The wake job is constructed by WorkManager, outside your object graph, so it finds the
// engine here:
UploaderEngineRegistry.register(uploader)
```

iOS needs three more lines in Swift and one entry in `Info.plist` — see
[`05-platform-notes.md`](05-platform-notes.md).

## 8. Optional — pause while offline

```kotlin
class NetworkConstraint(observer: ConnectivityObserver, scope: CoroutineScope) : ConstraintProvider {
    override val key: String = "network"
    override val satisfied: StateFlow<Boolean> =
        observer.isOnline.stateIn(scope, SharingStarted.Eagerly, initialValue = true)
}
```

Register it with the engine (`constraintProviders = listOf(networkConstraint)`) and name it on the
handler (`override val constraintKeys = setOf("network")`). Gated effects then wait while offline
and fire the moment connectivity returns, instead of burning retries against a dead radio.

Seed it optimistically, as above: a wrong `true` costs one failed attempt, a wrong `false` stalls
the queue until the first real signal.

Next: [`03-guide.md`](03-guide.md) for ordering, give-up policies, logout wipes, and detached
delivery.
