package io.github.jamal_wia.kmptoolkit.core

/**
 * True when [path] is absolute for the file system the code is running on.
 *
 * The one place the suite decides "absolute", instead of `path.startsWith('/')`, which is wrong on a
 * Windows JVM: there `C:\data` and `\\server\share\x` are absolute and `/data` is not. On Android and
 * on the desktop JVM this is `java.io.File(path).isAbsolute`; on iOS, where `/` is the only
 * separator, it is `path.startsWith('/')`. An empty string is never absolute.
 *
 * Only for a path the file system handed out or the consumer passed in. A name the code makes up
 * itself (`tmp/expect/<id>.sha256`) is a logical, always `/`-separated, never-absolute path and is
 * joined with `File(parent, child)`, not checked with this function.
 *
 * @since 4.1.0
 */
@ToolkitInternalApi
public expect fun isAbsoluteFilePath(path: String): Boolean
