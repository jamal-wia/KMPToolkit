package io.github.jamal_wia.kmptoolkit.video.player.javafx

import java.io.File

/**
 * Entry point of a child JVM for [CrashTolerantChildAdversarialTest]: on the first start (no marker file yet) it
 * writes an `hs_err` log of its own pid naming CoreVideo as the problematic frame and halts with 137, the way
 * the known crash ends a JVM under the OnError flag; on the next start it prints a result and exits 0. With
 * the argument `other` its fake log names a different frame, and with `always` it never stops crashing.
 */
internal object CrashRetryMain {

    @JvmStatic
    fun main(args: Array<String>) {
        val marker = File(args[0])
        val mode: String = args.getOrElse(1) { "once" }
        if (mode == "always" || !marker.exists()) {
            marker.writeText("crashed")
            val frame: String = if (mode == "other") "V  [libjvm.dylib+0x1]  SomethingElse::run()+0x10" else "C  [CoreVideo+0x23b0]  CVHWTime::reset()+0x40"
            val dir = File(System.getProperty(HS_ERR_DIR_PROPERTY) ?: error("no hs_err directory"))
            dir.mkdirs()
            File(dir, "hs_err_pid${ProcessHandle.current().pid()}.log").writeText("# A fatal error (fake)\n#\n# Problematic frame:\n# $frame\n")
            Runtime.getRuntime().halt(137)
        }
        println("RESULT survived=true")
    }
}
