plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
    id("kmptoolkit.androidtest")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Hijri")
    pomDescription.set(
        "Umm al-Qura date conversion: a HijriDate value and a LocalDate.toHijriDate() that reads " +
            "the tables the platform already ships — ICU on Android, NSCalendar on iOS. Pick this " +
            "module if your shared Kotlin code needs the Islamic civil date and you would rather " +
            "not hand-roll arithmetic that disagrees with the phone's own calendar for whole " +
            "months at a time. It returns numbers only: month names and any sighting correction " +
            "are the consuming app's to apply."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.hijri"
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    // The jvm half is real, not a stub: the JDK ships the same Umm al-Qura tables as
    // `java.time.chrono.HijrahDate`, so code shared with desktop converts dates there too.
    jvm()

    sourceSets {
        commonMain.dependencies {
            // The one dependency, and it is the API: a calendar library that cannot take a date
            // would push the same conversion boilerplate into every consumer. Nothing else is
            // pulled in — the conversion itself is platform tables already on the classpath.
            api(libs.kotlinx.datetime)
        }
    }
}
