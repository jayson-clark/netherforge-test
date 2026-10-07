package dev.netherforge.plugin.project

import dev.netherforge.format.project.KindSpec
import dev.netherforge.format.project.Kinds
import dev.netherforge.format.project.Names
import dev.netherforge.format.project.PackagePaths

/**
 * The unit hot reload works in: one resource, a kind from format's registry
 * ([Kinds]) and an id. A saved file reloads the resource that owns it, once,
 * however many of its files were in the batch.
 */
data class Resource(val kind: KindSpec<*, *>, val id: String) {
    /** As the console names it: `centity:tower`, `recipe:ruby_sword`, `map:arena`. */
    val key: String get() = "${kind.id}:$id"

    /** Where it is: its folder (`menus/shop`), or its one file (`recipes/ruby.json`). */
    val location: String get() = kind.locationOf(id)

    /** Whether [file] (a project path) is one of its files: its folder's, or the document beside its file. */
    fun owns(file: String): Boolean = file == location || file.startsWith("$location/") || file == kind.companionPathOf(id)

    companion object {
        /**
         * The resource a project-relative path belongs to, or null for files
         * no resource owns (the manifest, a README, `.gitignore`, anything in
         * `.netherforge/`) and for an id that can't be one.
         */
        fun of(path: String): Resource? {
            val found = Kinds.classify(path) ?: return null
            val kind = found.kind?.let(Kinds::byId) ?: return null
            val id = found.id?.takeIf(Names::isId) ?: return null
            // A package's resource is named as the runtime names it: `acme:coin`.
            return Resource(kind, found.pkg?.let { PackagePaths.of(it, id) } ?: id)
        }
    }
}
