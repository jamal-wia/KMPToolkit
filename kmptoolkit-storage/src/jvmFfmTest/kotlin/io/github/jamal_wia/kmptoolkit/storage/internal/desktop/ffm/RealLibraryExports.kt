package io.github.jamal_wia.kmptoolkit.storage.internal.desktop.ffm

/**
 * What the real libsecret and glib libraries export, as a model for tests of the Linux binding. It is data about the LIBRARIES,
 * never read back from the binding: a name the binding asks for that is not in here is a symbol that does not exist (the defect
 * of `secret_backend_get_sync`, which libsecret never had), and a test built on this table fails on it.
 *
 * - [libsecret]: the symbols the binding may use, each with the libsecret version that first exported it, from the symbols file
 *   Debian ships for `libsecret-1.so.0` (`debian/libsecret-1-0.symbols`, package 0.21.8.2-2) and checked against the headers of
 *   GNOME/libsecret master (`secret-password.h`, `secret-retrievable.h`, `secret-value.h`, `secret-collection.h`,
 *   `secret-item.h`, `secret-backend.h`). The header says "Since: 0.19.0" for `secret_backend_get`, but the exports start at 0.20.1
 *   (the 0.19 build does not contain `secret-backend.c`), so the symbols file is the truth for what a binding can resolve.
 *   A new name goes in only after it was looked up in those two sources.
 * - [glib]: the glib, gobject and gio functions the binding may use, each checked against the glib 2.x headers
 *   (`glib/gmain.h`, `glib/ghash.h`, `glib/glist.h`, `glib/gerror.h`, `glib/gquark.h`, `glib/gmem.h`, `gobject/gobject.h`,
 *   `gobject/gtype.h`, `gio/gdbuserror.h`).
 */
internal object RealLibraryExports {

    /** The libsecret symbols the binding may resolve, with the version that first exported each. */
    private val LIBSECRET: Map<String, String> = linkedMapOf(
        "secret_password_search_sync" to "0.19.1",
        "secret_password_searchv_sync" to "0.19.1",
        "secret_retrievable_retrieve_secret_sync" to "0.19.1",
        "secret_password_storev_sync" to "0.7",
        "secret_password_clearv_sync" to "0.7",
        "secret_value_get" to "0.7",
        "secret_value_unref" to "0.7",
        "secret_collection_for_alias_sync" to "0.7",
        "secret_collection_get_locked" to "0.7",
        "secret_item_get_type" to "0.7",
        "secret_item_get_locked" to "0.7",
        "secret_backend_get" to "0.20.1",
        "secret_backend_get_finish" to "0.20.1",
    )

    /** The glib, gobject and gio functions the binding may resolve. */
    val glib: Set<String> = setOf(
        "g_str_hash", "g_str_equal", "g_hash_table_new", "g_hash_table_insert", "g_hash_table_unref", "g_list_free",
        "g_error_free", "g_free", "g_quark_to_string", "g_object_unref", "g_type_name_from_instance",
        "g_type_check_instance_is_a", "g_dbus_error_get_remote_error", "g_dbus_error_encode_gerror",
        "g_main_context_new", "g_main_context_push_thread_default", "g_main_context_pop_thread_default", "g_main_context_unref",
        "g_main_context_iteration",
    )

    /** The libsecret symbols that libsecret [version] exports. */
    fun libsecret(version: String): Set<String> =
        LIBSECRET.filterValues { since: String -> compareVersions(since, version) <= 0 }.keys

    /** Every name a library of libsecret [version] and glib exports (of those the binding may use). */
    fun all(version: String): Set<String> = libsecret(version) + glib

    /** -1, 0 or 1 for dotted numeric versions ("0.20.1" is below "0.21.8.2"). */
    fun compareVersions(left: String, right: String): Int {
        val a: List<Int> = left.split('.').map(String::toInt)
        val b: List<Int> = right.split('.').map(String::toInt)
        for (index: Int in 0 until maxOf(a.size, b.size)) {
            val difference: Int = a.getOrElse(index) { 0 }.compareTo(b.getOrElse(index) { 0 })
            if (difference != 0) return difference
        }
        return 0
    }
}
