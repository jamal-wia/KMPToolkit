package io.github.jamal_wia.kmptoolkit.logging

internal actual fun Throwable.isLinkageError(): Boolean = this is LinkageError

internal actual fun Throwable.isInterruptSignal(): Boolean = this is InterruptedException

internal actual fun restoreInterruptFlag() {
    Thread.currentThread().interrupt()
}
