package io.github.jamal_wia.kmptoolkit.files.testing

import io.github.jamal_wia.kmptoolkit.files.Durability
import io.github.jamal_wia.kmptoolkit.files.ExistingFile
import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.FileKind
import io.github.jamal_wia.kmptoolkit.files.FileMetadata
import io.github.jamal_wia.kmptoolkit.files.FileResult
import io.github.jamal_wia.kmptoolkit.files.MoveOptions
import io.github.jamal_wia.kmptoolkit.files.WriteOptions
import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import io.github.jamal_wia.kmptoolkit.files.getOrNull
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round 4 of the breaker for unit G (character: model-based properties). A small in-memory model of a directory tree,
 * written from the documented contract (docs/kmptoolkit-files/04-api-reference.md), runs in lock step with the real
 * engine on a temporary directory and with `FakeFileAccess`: random seeded sequences of createDirectories, write,
 * move, copy (read then write), delete, deleteRecursively, list, metadata, readBytes and treeSize. After every step
 * the real and the fake results must be identical (typed error kind and masked path), both must agree with the model
 * (success, or an error kind the documentation allows for that situation), and the whole trees must be equal.
 * Seeds: three fixed ones x 200 steps (override with -Dkmptoolkit.test.seed for one seed); the seed and the step
 * number are in every failure message.
 */
class ModelSequenceR4Test {

    private sealed interface Node {
        data object Dir : Node
        class Data(val bytes: ByteArray) : Node
    }

    /** What the contract allows for one step: a required outcome, or an error kind out of [kinds]. */
    private class Expect(val success: Boolean?, val kinds: Set<String> = emptySet(), val value: String? = null)

    private class Model {
        val nodes: MutableMap<List<String>, Node> = HashMap<List<String>, Node>().also { it[emptyList()] = Node.Dir }

        enum class Chain { Ok, Missing, NotDir }

        /** The state of the proper ancestors of [segments], walking down from the root. */
        fun chain(segments: List<String>): Chain {
            for (length: Int in 1 until segments.size) {
                when (nodes[segments.take(length)]) {
                    null -> return Chain.Missing
                    is Node.Data -> return Chain.NotDir
                    Node.Dir -> Unit
                }
            }
            return Chain.Ok
        }

        fun ensureAncestors(segments: List<String>) {
            for (length: Int in 1 until segments.size) nodes.putIfAbsent(segments.take(length), Node.Dir)
        }

        fun children(segments: List<String>): List<String> =
            nodes.keys.filter { it.size == segments.size + 1 && it.take(segments.size) == segments }.map { it.last() }.sorted()

        fun removeSubtree(segments: List<String>) {
            nodes.keys.removeAll { it.size >= segments.size && it.take(segments.size) == segments }
        }

        fun size(segments: List<String>): Long =
            nodes.entries.filter { it.key.size >= segments.size && it.key.take(segments.size) == segments }
                .sumOf { (it.value as? Node.Data)?.bytes?.size?.toLong() ?: 0L }

        fun render(): List<String> = nodes.entries.filter { it.key.isNotEmpty() }.map { (key: List<String>, node: Node) ->
            key.joinToString("/") + when (node) {
                Node.Dir -> " D"
                is Node.Data -> " F " + node.bytes.decodeToString()
            }
        }.sorted()
    }

    private class World(val files: FileAccess, val root: String, val cleanup: () -> Unit)

    private fun realWorld(): World {
        val dir: java.nio.file.Path = Files.createTempDirectory("kmptoolkit-model-r4").toRealPath()
        return World(createFileAccess(), dir.toString()) { dir.toFile().deleteRecursively() }
    }

    private fun fakeWorld(): World {
        val fake: FakeFileAccess = FakeFileAccess(caseSensitive = true)
        fake.createDirectory("/r")
        return World(fake, "/r") {}
    }

    private fun kindOf(error: FileError): String = error::class.simpleName.orEmpty()

    private fun renderResult(world: World, value: Any?): String {
        val text: String = when (value) {
            is FileResult.Success<*> -> "OK(" + renderResult(world, value.value) + ")"
            is FileResult.Failure -> kindOf(value.error) + "(" + (value.error.path ?: "-") + ")"
            is FileMetadata -> "meta:" + value.kind + ":" + value.sizeBytes
            is ByteArray -> "bytes:" + value.decodeToString()
            is List<*> -> value.joinToString(prefix = "[", postfix = "]") { renderResult(world, it) }
            null -> "null"
            else -> value.toString()
        }
        return text.replace(world.root, "<R>")
    }

    private fun tree(world: World): List<String> {
        val lines: MutableList<String> = ArrayList()
        fun walk(directory: String, prefix: String) {
            val names: List<String> = world.files.list(directory).getOrNull() ?: return
            for (name: String in names) {
                val path: String = directory + "/" + name // path-hygiene-ignore: PH4 the model differential spells every path with '/' on purpose, the Windows JDK accepts it
                when (world.files.metadata(path, false)?.kind) {
                    FileKind.RegularFile -> lines += prefix + name + " F " + world.files.readBytes(path, 1_000_000).getOrNull()?.decodeToString()
                    FileKind.Directory -> {
                        lines += "$prefix$name D"
                        walk(path, "$prefix$name/")
                    }
                    else -> lines += "$prefix$name ?"
                }
            }
        }
        walk(world.root, "")
        return lines.sorted()
    }

    private val names: List<String> = listOf("a", "b", "c")

    private fun randomSegments(random: Random, allowRoot: Boolean): List<String> {
        val depth: Int = random.nextInt(if (allowRoot) 0 else 1, 4)
        return (0 until depth).map { names[random.nextInt(names.size)] }
    }

    /** A spelling of the entry: doubled separators, a `.` segment and a trailing separator all name the same entry. */
    private fun spell(root: String, segments: List<String>, random: Random): String {
        if (segments.isEmpty()) return root
        val builder: StringBuilder = StringBuilder(root)
        for (segment: String in segments) {
            builder.append(listOf("/", "/", "/", "//", "/./")[random.nextInt(5)]).append(segment)
        }
        if (random.nextInt(6) == 0) builder.append('/')
        return builder.toString()
    }

    private fun runSequence(seed: Long, steps: Int) {
        val random: Random = Random(seed)
        val real: World = realWorld()
        val fake: World = fakeWorld()
        val model: Model = Model()
        var successes: Int = 0
        try {
            for (step: Int in 0 until steps) {
                val at: String = "seed=$seed step=$step"
                val spellSeed: Long = random.nextLong()
                // One operation, built once, executed on both worlds and judged by the model.
                val opNumber: Int = random.nextInt(100)
                val target: List<String> = randomSegments(random, allowRoot = opNumber >= 70)
                val second: List<String> = randomSegments(random, allowRoot = false)
                val payload: ByteArray = "v$step-${random.nextInt(1000)}".encodeToByteArray()
                val existing: ExistingFile = if (random.nextInt(4) == 0) ExistingFile.Fail else ExistingFile.Replace
                val createParent: Boolean = random.nextInt(3) == 0
                val durability: Durability = Durability.entries[random.nextInt(Durability.entries.size)]
                val maxBytes: Long = longArrayOf(0L, 3L, 16L, 1_000L)[random.nextInt(4)]
                val label: String
                val expect: Expect
                val apply: () -> Unit
                var onFailure: () -> Unit = {}
                val exec: (World, Random) -> Any?
                when {
                    opNumber < 15 -> {
                        label = "createDirectories ${target.joinToString("/")}"
                        exec = { w, r -> w.files.createDirectories(spell(w.root, target, r), durable = durability != Durability.Buffered) }
                        var blocked: Boolean = false
                        for (length: Int in 1..target.size) if (model.nodes[target.take(length)] is Node.Data) blocked = true
                        expect = if (blocked) Expect(false, setOf("NotADirectory")) else Expect(true)
                        apply = { for (length: Int in 1..target.size) model.nodes.putIfAbsent(target.take(length), Node.Dir) }
                    }
                    opNumber < 35 -> {
                        label = "write ${target.joinToString("/")} $existing parent=$createParent"
                        exec = { w, r ->
                            w.files.writeAtomically(
                                spell(w.root, target, r), payload,
                                WriteOptions(durability = durability, existing = existing, createParentDirectories = createParent),
                            )
                        }
                        val chain: Model.Chain = model.chain(target)
                        val node: Node? = model.nodes[target]
                        expect = when {
                            chain == Model.Chain.NotDir -> Expect(false, setOf("NotADirectory"))
                            chain == Model.Chain.Missing -> if (createParent) Expect(true) else Expect(false, setOf("NotFound"))
                            node == Node.Dir -> Expect(false, if (existing == ExistingFile.Fail) setOf("IsADirectory", "AlreadyExists") else setOf("IsADirectory"))
                            node is Node.Data && existing == ExistingFile.Fail -> Expect(false, setOf("AlreadyExists"))
                            else -> Expect(true)
                        }
                        apply = { model.ensureAncestors(target); model.nodes[target] = Node.Data(payload) }
                    }
                    opNumber < 50 -> {
                        label = "move ${target.joinToString("/")} -> ${second.joinToString("/")} $existing parent=$createParent"
                        exec = { w, r ->
                            w.files.move(spell(w.root, target, r), spell(w.root, second, r), MoveOptions(existing = existing, createParentDirectories = createParent))
                        }
                        val fromNode: Node? = model.nodes[target]
                        val fromChain: Model.Chain = model.chain(target)
                        val toChain: Model.Chain = model.chain(second)
                        val toNode: Node? = model.nodes[second]
                        // Documented order (MoveOptions: creates the parent of the destination "first"): once the source
                        // exists the parent is made, and it stays when the move then fails for another reason (batch finding G17).
                        if (createParent && fromNode != null && toChain != Model.Chain.NotDir) onFailure = { model.ensureAncestors(second) }
                        val isSame: Boolean = target == second
                        val underSource: Boolean = fromNode == Node.Dir && second.size > target.size && second.take(target.size) == target
                        if (isSame || underSource) {
                            // Outside the model on purpose (rename onto itself, a directory into itself): covered by the
                            // differential-only test below, where the model plays no part.
                            expect = Expect(null)
                            apply = {}
                        } else {
                            val kinds: MutableSet<String> = HashSet()
                            if (fromNode == null && fromChain != Model.Chain.NotDir) kinds += "NotFound"
                            if (fromChain == Model.Chain.NotDir) { kinds += "NotADirectory"; kinds += "NotFound" }
                            if (fromNode == Node.Dir && existing == ExistingFile.Fail) kinds += "Unsupported"
                            if (toChain == Model.Chain.NotDir) kinds += "NotADirectory"
                            if (toChain == Model.Chain.Missing && !createParent) kinds += "NotFound"
                            if (toNode == Node.Dir) { kinds += "IsADirectory"; if (existing == ExistingFile.Fail) kinds += "AlreadyExists" }
                            if (toNode is Node.Data && existing == ExistingFile.Fail) kinds += "AlreadyExists"
                            if (toNode is Node.Data && fromNode == Node.Dir) kinds += "NotADirectory"
                            expect = if (kinds.isEmpty()) Expect(true) else Expect(false, kinds)
                            apply = {
                                val moved: Map<List<String>, Node> = model.nodes.filterKeys { it.size >= target.size && it.take(target.size) == target }
                                model.removeSubtree(target)
                                model.ensureAncestors(second)
                                if (toNode is Node.Data) model.nodes.remove(second)
                                for ((key: List<String>, node: Node) in moved) model.nodes[second + key.drop(target.size)] = node
                            }
                        }
                    }
                    opNumber < 55 -> {
                        label = "copy ${target.joinToString("/")} -> ${second.joinToString("/")}"
                        exec = { w, r ->
                            when (val read: FileResult<ByteArray> = w.files.readBytes(spell(w.root, target, r), 1_000L)) {
                                is FileResult.Failure -> read
                                is FileResult.Success -> w.files.writeAtomically(spell(w.root, second, r), read.value, WriteOptions(durability = Durability.Buffered))
                            }
                        }
                        val fromNode: Node? = model.nodes[target]
                        val toChain: Model.Chain = model.chain(second)
                        val toNode: Node? = model.nodes[second]
                        val kinds: MutableSet<String> = HashSet()
                        if (model.chain(target) != Model.Chain.Ok || fromNode == null) { kinds += "NotFound"; kinds += "NotADirectory" }
                        else if (fromNode == Node.Dir) kinds += "IsADirectory"
                        else {
                            if (toChain == Model.Chain.NotDir) kinds += "NotADirectory"
                            if (toChain == Model.Chain.Missing) kinds += "NotFound"
                            if (toNode == Node.Dir) kinds += "IsADirectory"
                        }
                        expect = if (kinds.isEmpty()) Expect(true) else Expect(false, kinds)
                        apply = { model.nodes[second] = Node.Data((fromNode as Node.Data).bytes) }
                    }
                    opNumber < 62 -> {
                        label = "delete ${target.joinToString("/")}"
                        exec = { w, r -> w.files.delete(spell(w.root, target, r)) }
                        val node: Node? = model.nodes[target]
                        expect = when {
                            model.chain(target) == Model.Chain.NotDir -> Expect(null, setOf("NotADirectory", "NotFound"))
                            node == null -> Expect(true)
                            node == Node.Dir && model.children(target).isNotEmpty() -> Expect(false, setOf("DirectoryNotEmpty"))
                            else -> Expect(true)
                        }
                        apply = { model.nodes.remove(target) }
                    }
                    opNumber < 70 -> {
                        label = "deleteRecursively ${target.joinToString("/")}"
                        exec = { w, r -> w.files.deleteRecursively(spell(w.root, target, r)) }
                        expect = if (model.chain(target) == Model.Chain.NotDir) Expect(null, setOf("NotADirectory", "NotFound")) else Expect(true)
                        apply = { model.removeSubtree(target) }
                    }
                    opNumber < 78 -> {
                        label = "list ${target.joinToString("/")}"
                        exec = { w, r -> w.files.list(spell(w.root, target, r)) }
                        val node: Node? = model.nodes[target]
                        expect = when {
                            model.chain(target) == Model.Chain.NotDir -> Expect(null, setOf("NotADirectory"), "OK([])")
                            node is Node.Data -> Expect(false, setOf("NotADirectory"))
                            node == null -> Expect(true, value = "OK([])")
                            else -> Expect(true, value = "OK(" + model.children(target).joinToString(prefix = "[", postfix = "]") + ")")
                        }
                        apply = {}
                    }
                    opNumber < 86 -> {
                        label = "metadata ${target.joinToString("/")}"
                        exec = { w, r -> w.files.metadata(spell(w.root, target, r), followLinks = r.nextBoolean()) }
                        val node: Node? = if (model.chain(target) == Model.Chain.Ok) model.nodes[target] else null
                        expect = Expect(
                            true,
                            value = when (node) {
                                null -> "null"
                                Node.Dir -> "meta:Directory:0"
                                is Node.Data -> "meta:RegularFile:${node.bytes.size}"
                            },
                        )
                        apply = {}
                    }
                    opNumber < 94 -> {
                        label = "readBytes ${target.joinToString("/")} max=$maxBytes"
                        exec = { w, r -> w.files.readBytes(spell(w.root, target, r), maxBytes) }
                        val node: Node? = model.nodes[target]
                        expect = when {
                            model.chain(target) == Model.Chain.NotDir -> Expect(false, setOf("NotADirectory", "NotFound"))
                            node == null -> Expect(false, setOf("NotFound"))
                            node == Node.Dir -> Expect(false, setOf("IsADirectory"))
                            (node as Node.Data).bytes.size > maxBytes -> Expect(false, setOf("TooLarge"))
                            else -> Expect(true, value = "OK(bytes:" + node.bytes.decodeToString() + ")")
                        }
                        apply = {}
                    }
                    else -> {
                        label = "treeSize ${target.joinToString("/")}"
                        exec = { w, r -> w.files.treeSize(spell(w.root, target, r)) }
                        expect = Expect(true, value = "OK(" + (if (model.chain(target) == Model.Chain.Ok) model.size(target) else 0L) + ")")
                        apply = {}
                    }
                }
                // Both worlds use the same spelling stream: a fresh Random with the same seed per world.
                val rawReal: Any? = exec(real, Random(spellSeed))
                val rawFake: Any? = exec(fake, Random(spellSeed))
                val realResult: String = renderResult(real, rawReal)
                val fakeResult: String = renderResult(fake, rawFake)
                if (expect.success == null && expect.kinds.isEmpty()) {
                    // A move onto itself or into itself is unspecified by the docs and kernel dependent (macOS answers ENOTDIR before
                    // EINVAL for a directory moved onto a file inside itself, the fake the reverse): only success versus failure and the
                    // resulting trees must agree (observation O-5 of round 4).
                    assertEquals(realResult.startsWith("OK("), fakeResult.startsWith("OK("), "$at $label: success differs: $realResult versus $fakeResult")
                } else {
                    assertEquals(realResult, fakeResult, "$at $label: the fake and the real engine disagree")
                }
                val failed: Boolean = rawReal is FileResult.Failure
                val kind: String = (rawReal as? FileResult.Failure)?.let { kindOf(it.error) }.orEmpty()
                when (expect.success) {
                    true -> {
                        assertTrue(!failed, "$at $label: the model expects success, got $realResult")
                        if (expect.value != null) assertEquals(expect.value, realResult, "$at $label: value differs from the model")
                        apply()
                        successes++
                    }
                    false -> {
                        assertTrue(failed && kind in expect.kinds, "$at $label: the model expects an error of kind ${expect.kinds}, got $realResult")
                        onFailure()
                    }
                    null -> if (failed) {
                        assertTrue(expect.kinds.isEmpty() || kind in expect.kinds, "$at $label: error kind $kind outside ${expect.kinds}")
                        onFailure()
                    } else if (expect.value != null) {
                        assertEquals(expect.value, realResult, "$at $label: value differs from the model")
                    }
                }
                assertEquals(model.render(), tree(real), "$at $label: the real tree differs from the model")
                assertEquals(tree(real), tree(fake), "$at $label: the fake tree differs from the real tree")
            }
            assertTrue(successes > steps / 4, "seed=$seed only $successes of $steps steps succeeded: the generator is too hostile to prove anything")
        } finally {
            real.cleanup()
        }
    }

    private fun seeds(): List<Long> =
        System.getProperty("kmptoolkit.test.seed")?.toLongOrNull()?.let { listOf(it) } ?: listOf(40_431L, 40_432L, 40_433L)

    @Test
    fun `random sequences of two hundred steps agree between the model the real engine and the fake`() {
        for (seed: Long in seeds()) runSequence(seed, steps = 200)
    }

    @Test
    fun `a longer sequence on a different seed agrees too`() {
        runSequence(seeds().first() + 1_000L, steps = 400)
    }

    @Test
    fun `twelve more seeds of two hundred steps agree too`() {
        for (offset: Long in 1L..12L) runSequence(seeds().first() + 10_000L * offset, steps = 200)
    }

    @Test
    fun `moves onto itself and into itself behave the same on the real engine and the fake`() {
        val real: World = realWorld()
        val fake: World = fakeWorld()
        try {
            val results: MutableList<String> = ArrayList()
            for (world: World in listOf(real, fake)) {
                val log: MutableList<String> = ArrayList()
                fun p(relative: String): String = world.root + "/" + relative // path-hygiene-ignore: PH4 the differential spells paths with '/' on purpose
                fun note(label: String, value: Any?) { log += "$label -> " + renderResult(world, value) }
                world.files.createDirectories(p("d/e"))
                world.files.writeAtomically(p("f"), "F".encodeToByteArray())
                world.files.writeAtomically(p("d/e/g"), "G".encodeToByteArray())
                for (existing: ExistingFile in ExistingFile.entries) {
                    val o: MoveOptions = MoveOptions(existing = existing)
                    note("file onto itself $existing", world.files.move(p("f"), p("f"), o))
                    note("file onto itself with dot $existing", world.files.move(p("f"), p("./f"), o))
                    note("dir onto itself $existing", world.files.move(p("d"), p("d"), o))
                    note("dir into itself $existing", world.files.move(p("d"), p("d/e/x"), o))
                    note("dir into its child dir $existing", world.files.move(p("d"), p("d/e"), o))
                    note("child over its parent $existing", world.files.move(p("d/e"), p("d"), o))
                    note("file into itself as dir $existing", world.files.move(p("f"), p("f/x"), o))
                    note("dir over a file $existing", world.files.move(p("d"), p("f"), o))
                    note("file over a dir $existing", world.files.move(p("f"), p("d"), o))
                    note("tree after $existing", tree(world))
                }
                results += log.joinToString("\n")
            }
            assertEquals(results[0], results[1], "the real engine and the fake disagree on a self move")
        } finally {
            real.cleanup()
        }
    }
}
