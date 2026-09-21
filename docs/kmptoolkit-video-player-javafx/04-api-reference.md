# kmptoolkit-video-player-javafx — API reference

Package `io.github.jamal_wia.kmptoolkit.video.player.javafx`, `jvm` only. Every symbol below is
public API and covered by the module's ABI dump; anything not listed here is `internal` and may
change in any release. The player these functions return is the core module's `VideoPlayer` — see
[`kmptoolkit-video-player/04-api-reference.md`](../kmptoolkit-video-player/04-api-reference.md) for
its members.

---

## `createJavaFxVideoPlayer`

```kotlin
public fun createJavaFxVideoPlayer(
    config: VideoPlayerConfig = VideoPlayerConfig(),
    coroutineContext: CoroutineContext = Dispatchers.Default,
): VideoPlayer
```

Creates a `VideoPlayer` whose engine is JavaFX Media, rendering into memory.

- **Touches no JavaFX class.** Safe to call on a classpath without OpenJFX; the missing runtime is
  reported by the first `prepare` as `Error(JavaFxVideoPlayerException.RuntimeUnavailable)`.
- **The first `prepare` starts the JavaFX toolkit** if nothing has started it yet — see
  [`05-platform-notes.md`](05-platform-notes.md#threading-and-the-javafx-toolkit).
- `config` and `coroutineContext` mean exactly what they mean for the core module's
  `createVideoPlayer`: `coroutineContext` hosts the position-polling coroutine only.
- Each call creates an independent player with its own native JavaFX `MediaPlayer`; release each one.
- Thread-safety: callable from any thread. The returned player is as thread-safe as every
  `VideoPlayer`.

## `isJavaFxMediaAvailable`

```kotlin
public fun isJavaFxMediaAvailable(): Boolean
```

`true` when the OpenJFX `javafx.media` classes are on the classpath and the JavaFX toolkit runs or
could be started.

- **Side effect:** starts the toolkit when it is not running yet, exactly as the first `prepare`
  would. It does not create a player or open a source.
- **Remembered:** the first answer (success or failure) holds for the life of the process.
- **Not a format check:** `true` does not mean every source decodes on this OS.
- Blocks the calling thread for as long as the toolkit takes to start (typically well under a
  second, bounded at 10 s). Thread-safe.

## `JavaFxVideoPlayerException`

```kotlin
public sealed class JavaFxVideoPlayerException(message: String, cause: Throwable? = null) : Exception
```

The failures specific to this engine. A player reports each as `VideoPlayerState.Error(cause)` after
`prepare`. The messages are developer diagnostics, not text for a user. Every other failure is
passed through as the platform reported it (`FileNotFoundException`, `IllegalArgumentException`,
`javafx.scene.media.MediaException`) — see [`03-guide.md`](03-guide.md#errors-you-can-see).

Instances are created only by the library (the constructors are `internal`).

### `RuntimeUnavailable`

```kotlin
public class RuntimeUnavailable : JavaFxVideoPlayerException
```

The OpenJFX runtime is not on the classpath, or the JavaFX toolkit cannot start here (typically: no
display). `cause` is what JavaFX reported — a `ClassNotFoundException`, an `UnsatisfiedLinkError`, an
`UnsupportedOperationException("Unable to open DISPLAY")`, … Permanent for the process.

### `LoadTimedOut`

```kotlin
public class LoadTimedOut : JavaFxVideoPlayerException {
    public val timeoutMs: Long
}
```

JavaFX reported the source neither ready nor failed within `timeoutMs` (30 000 ms). JavaFX does this
for some sources it cannot play instead of raising an error; treat it as unplayable.

### `HeadersNotSupported`

```kotlin
public class HeadersNotSupported : JavaFxVideoPlayerException {
    public val headerNames: Set<String>
}
```

A `VideoSource.Remote` carried request headers, which JavaFX Media cannot send. Nothing was
requested from the network. `headerNames` lists the names that were supplied (not the values, which
may be credentials).

---

## Cross-module internals

The engine implements the core module's `@ToolkitInternalApi` `VideoFrameSource`, which is how
`kmptoolkit-video-player-compose` finds its frames through `VideoPlayer.frameSourceOrNull()`. That is
not public API of either module; do not rely on it from app code.
