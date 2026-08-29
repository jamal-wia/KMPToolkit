# kmptoolkit-session — Getting started

Five minutes from an empty project to a sign-out that wipes every feature's state exactly once.

## 1. Add the dependency

```kotlin
// build.gradle.kts of your shared module
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(platform("io.github.jamal-wia:kmptoolkit-bom:<version>"))
            implementation("io.github.jamal-wia:kmptoolkit-session")
        }
        commonTest.dependencies {
            implementation("io.github.jamal-wia:kmptoolkit-session-testing")
        }
    }
}
```

It is pure common Kotlin — no Android manifest entry, no `Info.plist` key, no permission, no
platform initialization. It pulls in `kotlinx-coroutines-core` and
[`kmptoolkit-logging`](../kmptoolkit-logging/01-overview.md).

## 2. Write a cleaner per feature that holds per-account state

A cleaner is one suspend function and a name. Put it next to the thing it wipes, not in some
central logout file — that is the whole point.

```kotlin
class ChatSessionCleaner(private val database: ChatDatabase) : SessionCleaner {
    override val name: String = "chat"

    override suspend fun clean() {
        database.clearAll()
    }
}
```

Three rules, spelled out in [`03-guide.md`](03-guide.md#writing-a-cleaner): a cleaner must be
idempotent, must not call the network, and must not assume any other cleaner has run.

## 3. Create one manager and hold it for the app's lifetime

```kotlin
val sessionManager: SessionManager = createSessionManager(
    cleaners = listOf(
        ChatSessionCleaner(chatDatabase),
        ProfileSessionCleaner(profileStore),
        DownloadsSessionCleaner(downloads),
    ),
    revoker = SessionRevoker { authApi.revokeCurrentSession() }, // optional
    logger = loggerFactory.logger("Session"),                    // optional
)
```

Teardown dispatches to `Dispatchers.IO` unless you pass a different `ioDispatcher` — production
code rarely needs to.

There is no DI framework here and no global instance — construct it wherever you construct your
other singletons. With Koin that is four lines:

```kotlin
val sessionModule = module {
    single<SessionManager> {
        createSessionManager(cleaners = getAll(), revoker = get())
    }
}
```

## 4. Tell it when a session starts

```kotlin
suspend fun signIn(email: String, password: String) {
    val credentials = authApi.signIn(email, password)
    credentialStore.save(credentials)   // your storage, not this module's business
    sessionManager.startSession()
}
```

At app launch, do the same thing for a session you restored from storage:

```kotlin
if (credentialStore.load() != null) sessionManager.startSession()
```

`state` starts at `INACTIVE` in every new process — this module persists nothing, so a restored
session is just a `startSession()` call.

## 5. React to the session ending

Nothing registers a callback. Everything that cares observes one flow:

```kotlin
sessionManager.state
    .onEach { state -> if (state == SessionState.INACTIVE) navigator.replaceAll(SignInScreen) }
    .launchIn(componentScope)
```

## 6. End the session

```kotlin
suspend fun signOut() {
    val report: SessionEndReport = sessionManager.endSession()
    if (!report.isClean) {
        logger.w { "Sign-out left work behind: ${report.cleanerFailures.map { it.name }}" }
    }
}
```

Call it from anywhere, as often as you like, from as many coroutines at once as you like — the
teardown runs once per session. `endSession()` suspends until the teardown is finished, and the
teardown is not cancellable once started, so it does not matter that the navigation it triggers
destroys the screen that called it.

The `report` is a record, not a decision: by the time you hold it the user is signed out. Nothing
in it can, or should, be used to keep them signed in.

## What next

- [`03-guide.md`](03-guide.md) — the cleaner contract in full, ordering, timeouts, offline
  sign-out, and the 401 path.
- [`04-api-reference.md`](04-api-reference.md) — every public symbol.
- [`06-testing.md`](06-testing.md) — the recording fixtures and what is worth asserting.
