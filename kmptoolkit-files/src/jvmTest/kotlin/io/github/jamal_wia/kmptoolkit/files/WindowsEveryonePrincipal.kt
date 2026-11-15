package io.github.jamal_wia.kmptoolkit.files

import java.nio.file.FileSystems
import java.nio.file.attribute.UserPrincipal
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Test helper of group files-owner-only (STO-1) for the Windows breaker classes: the well-known group S-1-1-0
 * ("Everyone"), resolved once and independent of the Windows display language. Its account name is localised
 * (`Everyone`, `Все`, `Jeder`, ...), the JDK lookup takes account names only (a SID string is refused) and has no public
 * way to build a principal from a SID. So the operating system translates the SID to the local name, printed as Base64
 * of UTF-8 so that no console code page can garble it, and that name is looked up. The JDK's Windows principals compare
 * by SID, so `==` against this value means "is S-1-1-0" whatever the name. Only touched on Windows.
 */
internal object WindowsEveryonePrincipal {
    private const val TRANSLATE_SCRIPT: String = "[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(" +
        "([System.Security.Principal.SecurityIdentifier]'S-1-1-0')" +
        ".Translate([System.Security.Principal.NTAccount]).Value))"

    val principal: UserPrincipal by lazy {
        val process: Process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", TRANSLATE_SCRIPT)
            .redirectErrorStream(true)
            .start()
        process.outputStream.close()
        val output: String = process.inputStream.bufferedReader(Charsets.US_ASCII).readText().trim()
        check(process.waitFor(60, TimeUnit.SECONDS)) { "hang: translating S-1-1-0" }
        check(process.exitValue() == 0) { "translating S-1-1-0 failed: $output" }
        val name = String(Base64.getDecoder().decode(output.lines().last().trim()), Charsets.UTF_8)
        FileSystems.getDefault().userPrincipalLookupService.lookupPrincipalByName(name)
    }
}
