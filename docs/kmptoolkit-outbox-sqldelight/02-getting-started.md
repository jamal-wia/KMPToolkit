# kmptoolkit-outbox-sqldelight — getting started

A durable queue, running, in about five minutes. Read
[`kmptoolkit-outbox/02-getting-started.md`](../kmptoolkit-outbox/02-getting-started.md) alongside
this — that module is where handlers, retries and enqueueing live; this one is only where the rows
go.

## 1. Add the dependency

```kotlin
// build.gradle.kts of your shared module
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(platform("io.github.jamal-wia:kmptoolkit-bom:<version>"))
            implementation("io.github.jamal-wia:kmptoolkit-outbox")
            implementation("io.github.jamal-wia:kmptoolkit-outbox-sqldelight")
        }
    }
}
```

You do **not** need the SQLDelight Gradle plugin for this. The queue's schema and its generated code
are inside the published artifact; the plugin is only needed if you also write `.sq` files of your
own.

There is no `-testing` companion artifact. The fixtures you would want —
`OutboxStoreContract`, `InMemoryOutboxStore`, `FakeOutbox` — are all in
`kmptoolkit-outbox-testing` and are storage-agnostic by design, so there is nothing SQLDelight-
specific left to ship. See [`kmptoolkit-outbox/06-testing.md`](../kmptoolkit-outbox/06-testing.md).

## 2. Open the storage, once per process

The factory is per-platform, because Android needs a `Context` and iOS needs nothing — see
[`docs/01-architecture.md`](../01-architecture.md).

```kotlin
// androidMain — Application.onCreate. The queue outlives every Activity, so nothing
// shorter-lived should own it.
val storage: OutboxStorage = createOutboxStorage(this)
```

```kotlin
// iosMain — once, from wherever you build your shared graph.
val storage: OutboxStorage = createOutboxStorage()
```

The file is created on first use, named after your app so nothing else can open it, and migrated
forward on an upgrade. Nothing else is required: no permission, no manifest entry, no `Info.plist`
key.

## 3. Hand both halves to the engine

```kotlin
// commonMain — shared code takes the SPI types, never the factory.
val engine: OutboxEngine = createOutboxEngine(
    store = storage.store,
    transactionRunner = storage.transactionRunner,
    scope = applicationScope,
    clock = OutboxClock.System,
)

engine.register(sendMessageHandler)
engine.start()
```

That is the whole integration. From here everything is `kmptoolkit-outbox`: register a handler,
call `enqueue`, and the row is persisted before the call returns.

## 4. Close it when the queue is done

```kotlin
storage.close()
```

`close` releases the database thread and, for a storage that opened its own file, the connection.
Do it when the queue itself is finished with — not on a screen teardown; the queue is supposed to
outlive the UI. Calling it twice is a no-op.

## Where to go from here

The standalone file above gives you a **durable** queue but not an **atomic** one: your domain
tables are in a different database, so no transaction spans both, and there is still a window where
a row says "sending" with nothing queued to send it.

Closing that window means putting the queue inside the database you already have — which is one
more factory call and one migration. That is the first section of
[`03-guide.md`](03-guide.md).
