# kmptoolkit-core — Getting started

There is nothing to get started with. `kmptoolkit-core` is not meant to be depended on directly, so
this page only says how to see which release of it you have and what to do if you landed here from
a compile error.

## It arrives by itself

Adding `kmptoolkit-video-player`, `kmptoolkit-audio-player` or `kmptoolkit-audio-recorder` resolves
`io.github.jamal-wia:kmptoolkit-core` at the same version, transitively. If you use the BOM
(see [`00-getting-started.md`](../00-getting-started.md)), the BOM pins it too:

```kotlin
commonMain.dependencies {
    implementation(platform("io.github.jamal-wia:kmptoolkit-bom:<version>"))
    implementation("io.github.jamal-wia:kmptoolkit-audio-player")   // brings kmptoolkit-core with it
}
```

Targets: Android, iOS and `jvm`.

## If you got here from a compile error

`This declaration needs opt-in. Its usage must be marked with '@io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi'`
means the symbol you called is not part of the public contract. Look in the module's own
`04-api-reference.md` for the supported way to do what you wanted; if there is none, open an issue
rather than opting in.
