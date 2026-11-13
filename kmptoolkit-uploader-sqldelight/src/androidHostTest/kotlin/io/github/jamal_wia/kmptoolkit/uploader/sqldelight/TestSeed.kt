package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

/**
 * The seed of a test's random choices: [default], or the value of `-Dkmptoolkit.test.seed` when the property is set
 * (the property is read from the test JVM, so it has to reach it, for example through a Gradle `systemProperty`).
 * Every failure message of a seeded test quotes the value it used.
 */
internal fun testSeed(default: Long): Long = java.lang.Long.getLong("kmptoolkit.test.seed", default)
