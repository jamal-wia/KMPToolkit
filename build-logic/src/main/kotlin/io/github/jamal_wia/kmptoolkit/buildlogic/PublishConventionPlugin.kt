package io.github.jamal_wia.kmptoolkit.buildlogic

import com.vanniktech.maven.publish.JavaPlatform
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create

/**
 * Per-module POM fields that can't be derived mechanically — set from each module's own
 * `build.gradle.kts`:
 *
 * ```
 * kmptoolkitPublish {
 *     pomName.set("KMPToolkit Coroutines")
 *     pomDescription.set("Pick this module if you need a testable dispatcher seam ...")
 * }
 * ```
 */
interface KmptoolkitPublishExtension {
    val pomName: Property<String>
    val pomDescription: Property<String>
}

/**
 * `kmptoolkit.publish` — the vanniktech `mavenPublishing { }` block, written once instead of
 * copy-pasted into every module. `Paginator/paginator-core/build.gradle.kts` repeats ~60 lines of
 * this ten times over; with 17 kmptoolkit-* artifacts that papercut becomes unmaintainable, which
 * is the whole reason this plugin exists.
 *
 * `coordinates()` uses `project.name` as the artifactId rather than a hardcoded string: every
 * kmptoolkit-* module's Gradle project name already *is* its artifactId
 * (`:kmptoolkit-coroutines` → `kmptoolkit-coroutines`), so restating it would only invite the two
 * to drift apart.
 *
 * Signing is conditional so a contributor without a GPG key can still build and run
 * `publishToMavenLocal`; CI supplies an in-memory key via `ORG_GRADLE_PROJECT_signingInMemoryKey`
 * (same condition Paginator's build files use).
 */
class PublishConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.vanniktech.maven.publish")

            group = providers.gradleProperty("kmptoolkit.group").get()
            version = providers.gradleProperty("kmptoolkit.version").get()

            val publishExtension: KmptoolkitPublishExtension =
                extensions.create("kmptoolkitPublish", KmptoolkitPublishExtension::class.java)

            // Configured eagerly, not in afterEvaluate: the vanniktech extension finalizes its
            // own properties once the project is evaluated, so writing to them from an
            // afterEvaluate block fails with "The value for this property is final and cannot be
            // changed any further". Passing the extension's Providers straight through keeps this
            // lazy anyway — a module setting `kmptoolkitPublish.pomName` further down its own
            // build file is still picked up.
            extensions.configure<MavenPublishBaseExtension> {
                // The BOM is a `java-platform`, not a KMP module — it has no sources, no targets
                // and nothing to put in a sources jar, and asking for the KotlinMultiplatform
                // shape there fails. Detecting the plugin keeps every POM field below shared
                // between the two, which is the reason this convention exists.
                if (pluginManager.hasPlugin("java-platform")) {
                    configure(JavaPlatform())
                } else {
                    configure(KotlinMultiplatform(javadocJar = JavadocJar.Empty()))
                }
                publishToMavenCentral(automaticRelease = true)

                if (project.findProperty("signing.keyId") != null ||
                    project.findProperty("signingInMemoryKey") != null ||
                    System.getenv("ORG_GRADLE_PROJECT_signingInMemoryKey") != null
                ) {
                    signAllPublications()
                }

                coordinates(group.toString(), project.name, version.toString())

                pom {
                    name.set(publishExtension.pomName)
                    description.set(publishExtension.pomDescription)
                    url.set("https://github.com/jamal-wia/KMPToolkit")
                    inceptionYear.set("2026")

                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                            distribution.set("repo")
                        }
                    }

                    developers {
                        developer {
                            id.set("jamal-wia")
                            name.set("Jamal Aliev")
                            url.set("https://github.com/jamal-wia")
                        }
                    }

                    scm {
                        url.set("https://github.com/jamal-wia/KMPToolkit")
                        connection.set("scm:git:git://github.com/jamal-wia/KMPToolkit.git")
                        developerConnection.set(
                            "scm:git:ssh://git@github.com/jamal-wia/KMPToolkit.git"
                        )
                    }

                    issueManagement {
                        system.set("GitHub Issues")
                        url.set("https://github.com/jamal-wia/KMPToolkit/issues")
                    }

                    ciManagement {
                        system.set("GitHub Actions")
                        url.set("https://github.com/jamal-wia/KMPToolkit/actions")
                    }
                }
            }

            // A module that forgets these ships a POM Maven Central rejects at validation time —
            // far later, and far harder to trace back here. Fail at configuration instead.
            afterEvaluate {
                check(publishExtension.pomName.isPresent) {
                    "$path applies kmptoolkit.publish without setting kmptoolkitPublish.pomName."
                }
                check(publishExtension.pomDescription.isPresent) {
                    "$path applies kmptoolkit.publish without setting " +
                        "kmptoolkitPublish.pomDescription."
                }
            }
        }
    }
}
