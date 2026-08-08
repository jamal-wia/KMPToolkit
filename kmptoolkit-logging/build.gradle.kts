plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
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

android {
    namespace = "io.github.jamal_wia.kmptoolkit.logging"
}

kotlin {
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    // No commonMain dependencies at all: the whole point of this module is that adding it to a
    // consumer's graph pulls in nothing but the Kotlin stdlib. The platform default sinks use
    // android.util.Log and Kotlin/Native's println, both already on the platform classpath.
}
