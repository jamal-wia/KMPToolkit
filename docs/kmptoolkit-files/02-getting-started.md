# kmptoolkit-files — Getting started

## 1. Add the dependency

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.jamal-wia:kmptoolkit-files:<version>")
        }
        commonTest.dependencies {
            implementation("io.github.jamal-wia:kmptoolkit-files-testing:<version>")
        }
    }
}
```

The version is whatever `kmptoolkit.version` says in this repository's `gradle.properties`; with the
BOM, omit it. There is no permission to declare and nothing to add to `Info.plist` — see
[`05-platform-notes.md`](05-platform-notes.md). The module depends on `kotlinx-coroutines-core` and on
the internal support artifact `kmptoolkit-core`; nothing else.

## 2. Create the instance in your platform layer

The factories are per platform, declared separately with the same shape — shared code takes a
`FileAccess` as a parameter and never names a factory.

Android:

```kotlin
class MyApplication : Application() {
    lateinit var files: FileAccess
    lateinit var directories: AppDirectories

    override fun onCreate() {
        super.onCreate()
        files = createFileAccess()
        directories = createAppDirectories(this, files)
    }
}
```

iOS (Kotlin side of your shared module):

```kotlin
val files: FileAccess = createFileAccess()
val directories: AppDirectories = createAppDirectories(files)
```

Desktop JVM:

```kotlin
val files: FileAccess = createFileAccess()
val directories: AppDirectories = createAppDirectories(filesDirectory = "/home/me/.config/myapp")
```

`FileAccess` holds no open handle between calls, needs no release, and may be shared by the whole
app. Construction is cheap and cannot fail. To change the temporary-file prefix pass a config:
`createFileAccess(FileAccessConfig(tempPrefix = ".myapp-"))`.

## 3. One durable record from shared code

```kotlin
// The store knows one path and nothing about where it lives, so a test can give it any path.
class ProfileStore(private val files: FileAccess, private val path: String) {

    suspend fun save(json: String): FileResult<Unit> = files.withIo {
        writeAtomically(
            path,
            json.encodeToByteArray(),
            WriteOptions(durability = Durability.Synced, createParentDirectories = true),
        )
    }

    suspend fun load(): String? =
        files.withIo { readBytes(path, maxBytes = 1L shl 20).getOrNull()?.decodeToString() }
}

// Composition root (platform or shared wiring): the app's own directory, then the record path.
fun profileStore(files: FileAccess, directories: AppDirectories): ProfileStore? =
    when (val dir = directories.directory(AppRoot.Files, "profile")) {
        is FileResult.Success -> ProfileStore(files, Paths.join(dir.value, "profile.json"))
        is FileResult.Failure -> null
    }
```

What happened here:

- `directories.directory(AppRoot.Files, "profile")` created `<filesDir>/profile` (and returned its
  absolute path) on Android, `<Application Support>/profile` on iOS, `<filesDirectory>/profile` on
  the JVM. It is idempotent and safe to call from many threads. `Paths.join` follows the **host's**
  path rules (POSIX, or Windows on a Windows JVM), which is what a real path needs.
- `withIo` moved the blocking calls to `Dispatchers.IO` and gave the block a cancellation check.
  Every `FileAccess` function is blocking: never call one from the main thread.
- `writeAtomically` replaced the previous record whole, and `createParentDirectories` made the
  directory when it was missing. If the process dies at any point, `load()` returns the previous
  record or the new one — never half of one.
- `readBytes` took a limit. A file larger than the limit is `FileError.TooLarge`, not an
  out-of-memory error.
- `getOrNull()` collapsed the typed result into `null` because this caller does not care why. When
  you do, branch on `FileResult.Failure.error`.

## 4. Test it without a disk

```kotlin
@Test
fun `a saved profile is loaded back`() = runTest {
    val files = FakeFileAccess()
    val store = ProfileStore(files, path = "/data/profile/profile.json")   // a string: the fake is POSIX on every host

    assertTrue(store.save("""{"name":"Ada"}""").isSuccess)
    assertEquals("""{"name":"Ada"}""", store.load())
}
```

`FakeFileAccess` runs the real engine over memory, so `createParentDirectories` creates `/data/profile`
in it exactly as on a device, and it can fail an operation or crash in the middle of a write — see
[`06-testing.md`](06-testing.md). Give the fake **string** paths, not `Paths.join` results: `Paths`
follows the host, so on a Windows test host it would join with `\`. `AppDirectories` is an interface; a
test that needs it implements it in a few lines, returning paths of the fake and creating the directory
with `files.createDirectories(...)`.

## Next

[`03-guide.md`](03-guide.md) covers choosing a durability, read-modify-write with `PathLocks`, the
leftovers of interrupted writes, recursive delete, cancellation and backup.
