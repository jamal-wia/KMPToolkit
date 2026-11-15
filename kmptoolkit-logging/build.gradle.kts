plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
    // Robolectric for androidHostTest: the platform sink writes through android.util.Log, which is
    // only assertable against its real code (the stack-trace rendering of a throwable chain).
    id("kmptoolkit.androidtest")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Logging")
    pomDescription.set(
        "A dependency-free logging seam: a Logger interface with lazy messages, a LogLevel " +
            "threshold, and a LogSink SPI that fans one event out to as many destinations as you " +
            "install. Pick this module if you want your shared Kotlin code to log without " +
            "choosing a logging framework for the app that consumes it — bridge the sink to " +
            "Kermit, Timber, os_log, or a crash reporter yourself."
    )
}

kotlin {
    android {
        namespace = "io.github.jamal_wia.kmptoolkit.logging"
    }

    iosArm64()
    iosSimulatorArm64()

    // The jvm half is real, not a stub: `Logger` is a `commonMain` parameter of the suite's shared
    // modules, so code shared with desktop needs the seam there too. Its sink writes to standard error.
    jvm()

    // No commonMain dependencies at all: the whole point of this module is that adding it to a
    // consumer's graph pulls in nothing but the Kotlin stdlib. The platform default sinks use
    // android.util.Log, Kotlin/Native's println and the JDK's System.err, all already on the
    // platform classpath.
}
