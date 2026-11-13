package io.github.jamal_wia.kmptoolkit.audio.recorder
// path-hygiene-file: logical-paths the table feeds POSIX-style absolute paths to the Android and iOS file systems, which both run on a POSIX host

/**
 * The path-shaping calls of [RecordingFileSystem] (`resolve`, `parentOf`, `isAbsolute`) as one table that the
 * Android and the iOS implementation are both held to. `resolve` joins "with the platform separator" and the
 * testing docs promise that a trailing or repeated separator of the directory is dropped, "as the real recorder
 * does"; `parentOf` answers the directory part or null if there is none. Each implementation calls [observe]
 * from its own platform test and compares with [EXPECTED]. Only the Android half is in the repository: the iOS
 * implementation does not meet the table (suspect R10-01).
 */
internal object RecordingFileSystemPathParityR10 {

    private val resolveCases: List<Pair<String, String>> = listOf(
        "/a/b" to "c.m4a",
        "/a/b/" to "c.m4a",
        "/a//b" to "c.m4a",
        "/a/b///" to "c.m4a",
        "/" to "c.m4a",
        "//" to "c.m4a",
        "/a/b" to "c d.m4a",
        "/a/b c" to "x.m4a",
        "/a/b" to "ünï.m4a",
        "/a/α" to "β.m4a",
        "/a/b" to "100%.m4a",
        "/a/b" to "100%25.m4a",
        "/a/b" to "a#b?c.m4a",
        "/a/b" to "a:b.m4a",
        "/a/b" to "x.m4a.",
        "/a/b" to ".hidden",
        "/a/b" to "..",
        "/a/b" to "",
    )

    private val parentCases: List<String> = listOf(
        "/a/b/c.m4a",
        "/a/b c/d.m4a",
        "/a/c.m4a",
        "/c.m4a",
        "/",
        "c.m4a",
        "/a//c.m4a",
        "/a/b/",
        "/a/α/β.m4a",
    )

    private val absoluteCases: List<String> = listOf("/a", "/", "", " ", "a", "a/b", "./a", "../a", "~/a", "//a")

    fun observe(fileSystem: RecordingFileSystem): List<String> = buildList {
        for ((directory: String, name: String) in resolveCases) add("resolve($directory|$name)=${fileSystem.resolve(directory, name)}")
        for (path: String in parentCases) add("parentOf($path)=${fileSystem.parentOf(path)}")
        for (path: String in absoluteCases) add("isAbsolute($path)=${fileSystem.isAbsolute(path)}")
    }

    /** What the documented contract says; a line that differs on one platform is a parity finding. */
    val EXPECTED: List<String> = listOf(
        "resolve(/a/b|c.m4a)=/a/b/c.m4a",
        "resolve(/a/b/|c.m4a)=/a/b/c.m4a",
        "resolve(/a//b|c.m4a)=/a/b/c.m4a",
        "resolve(/a/b///|c.m4a)=/a/b/c.m4a",
        "resolve(/|c.m4a)=/c.m4a",
        "resolve(//|c.m4a)=/c.m4a",
        "resolve(/a/b|c d.m4a)=/a/b/c d.m4a",
        "resolve(/a/b c|x.m4a)=/a/b c/x.m4a",
        "resolve(/a/b|ünï.m4a)=/a/b/ünï.m4a",
        "resolve(/a/α|β.m4a)=/a/α/β.m4a",
        "resolve(/a/b|100%.m4a)=/a/b/100%.m4a",
        "resolve(/a/b|100%25.m4a)=/a/b/100%25.m4a",
        "resolve(/a/b|a#b?c.m4a)=/a/b/a#b?c.m4a",
        "resolve(/a/b|a:b.m4a)=/a/b/a:b.m4a",
        "resolve(/a/b|x.m4a.)=/a/b/x.m4a.",
        "resolve(/a/b|.hidden)=/a/b/.hidden",
        "resolve(/a/b|..)=/a/b/..",
        "resolve(/a/b|)=/a/b",
        "parentOf(/a/b/c.m4a)=/a/b",
        "parentOf(/a/b c/d.m4a)=/a/b c",
        "parentOf(/a/c.m4a)=/a",
        "parentOf(/c.m4a)=/",
        "parentOf(/)=null",
        "parentOf(c.m4a)=null",
        "parentOf(/a//c.m4a)=/a",
        "parentOf(/a/b/)=/a",
        "parentOf(/a/α/β.m4a)=/a/α",
        "isAbsolute(/a)=true",
        "isAbsolute(/)=true",
        "isAbsolute()=false",
        "isAbsolute( )=false",
        "isAbsolute(a)=false",
        "isAbsolute(a/b)=false",
        "isAbsolute(./a)=false",
        "isAbsolute(../a)=false",
        "isAbsolute(~/a)=false",
        "isAbsolute(//a)=true",
    )

    fun mismatches(observed: List<String>): List<String> = buildList {
        for (i: Int in 0 until maxOf(observed.size, EXPECTED.size)) {
            val o: String = observed.getOrElse(i) { "<none>" }
            val e: String = EXPECTED.getOrElse(i) { "<none>" }
            if (o != e) add("observed [$o] expected [$e]")
        }
    }
}
