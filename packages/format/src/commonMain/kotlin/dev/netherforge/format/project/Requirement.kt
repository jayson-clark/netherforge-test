package dev.netherforge.format.project

import kotlinx.serialization.Serializable

/**
 * Something a package's scripts can do only when its `netherforge.json`
 * declares it (`"requires"`, [ProjectRequires]): the capabilities "Trust" in
 * PLAN.md's decisions is about. Its [id] is how the API spec's `requires`
 * names it (`moderation`, `db`, `http`, `plugin:vault`) and how the editor,
 * the server's log and `/nf requires` show it (`http:discord.com`).
 *
 * The one place a declaration is read: the runtime's check
 * (`Requirements.check`), the combined list the editor and the server show
 * ([combined]) and the messages saying what to declare ([declaration]) all
 * come from here. `packages/api/src/requirements.ts` mirrors [grantedBy] for
 * the editor's LuaLS stubs.
 */
sealed class Requirement {
    /** `moderation`, `db`, `http`, `http:discord.com`, `plugin:vault`. */
    abstract val id: String

    /** How a package declares it, as messages say it: `"requires": { "http": ["discord.com"] }`. */
    abstract val declaration: String

    /** Whether [requires] (a package's `requires`) declares it. */
    abstract fun grantedBy(requires: ProjectRequires?): Boolean

    override fun toString() = id

    /** Banning players, the whitelist, the message of the day and the most players allowed. */
    data object Moderation : Requirement() {
        override val id = "moderation"
        override val declaration = "\"requires\": { \"moderation\": true }"

        override fun grantedBy(requires: ProjectRequires?) = requires?.moderation == true
    }

    /** A database of the package's own (`nf.db`). */
    data object Db : Requirement() {
        override val id = "db"
        override val declaration = "\"requires\": { \"db\": true }"

        override fun grantedBy(requires: ProjectRequires?) = requires?.db == true
    }

    /**
     * HTTP requests to [host] (lowercase), or, with none, to any host the
     * package declares: what the spec's `requires: "http"` checks before the
     * function knows its URL. A request then checks its own host.
     */
    data class Http(val host: String? = null) : Requirement() {
        override val id = if (host == null) "http" else "http:$host"
        override val declaration = "\"requires\": { \"http\": [\"${host ?: "<host>"}\"] }"

        override fun grantedBy(requires: ProjectRequires?): Boolean {
            val hosts = requires?.http.orEmpty()
            return if (host == null) hosts.isNotEmpty() else hosts.any { allows(it, host) }
        }
    }

    /** Another plugin on the server, by its name in lowercase (`vault`). */
    data class Plugin(val name: String) : Requirement() {
        override val id = "plugin:$name"
        override val declaration = "\"requires\": { \"plugins\": [\"$name\"] }"

        override fun grantedBy(requires: ProjectRequires?) = name in requires?.plugins.orEmpty()
    }

    companion object {
        /** [id] as a requirement (`plugin:vault`), or null for text that isn't one. */
        fun parse(id: String): Requirement? = when {
            id == Moderation.id -> Moderation
            id == Db.id -> Db
            id == "http" -> Http()
            id.startsWith("http:") -> id.removePrefix("http:").takeIf(Names::isHostName)?.let(::Http)
            id.startsWith("plugin:") -> id.removePrefix("plugin:").takeIf(Names::isPluginName)?.let(::Plugin)
            else -> null
        }

        /**
         * Whether [pattern] (one of `requires.http`) allows a request to
         * [host]: the same name, or for `*.example.com` any name under
         * `example.com` (not `example.com` itself). Host names are compared
         * in lowercase, as DNS does.
         */
        fun allows(pattern: String, host: String): Boolean {
            val name = host.lowercase().removeSuffix(".")
            return if (pattern.startsWith("*.")) name.endsWith(pattern.substring(1)) else name == pattern
        }

        /**
         * What [requires] declares, one requirement each, in the order they're
         * shown: moderation, db, each host, each plugin. A host or plugin name
         * that isn't one (a problem in the manifest) declares nothing.
         */
        fun declared(requires: ProjectRequires?): List<Requirement> = buildList {
            if (requires == null) return@buildList
            if (requires.moderation == true) add(Moderation)
            if (requires.db == true) add(Db)
            requires.http.orEmpty().filter(Names::isHttpHost).distinct().sorted().forEach { add(Http(it)) }
            requires.plugins.orEmpty().filter(Names::isPluginName).distinct().sorted().forEach { add(Plugin(it)) }
        }

        /**
         * Every requirement declared across [snapshot]'s whole tree (the
         * project and every package it depends on, theirs included), each
         * with the packages that declare it: what whoever runs the server
         * agrees to by running the project. In [declared]'s order; packages
         * by namespace, the project's own first.
         */
        fun combined(snapshot: ProjectSnapshot): List<RequirementUse> {
            val namespaces = listOf(snapshot.namespace) + snapshot.packages.keys.sorted()
            val by = LinkedHashMap<Requirement, MutableList<String>>()
            for (namespace in namespaces) {
                for (requirement in declared(snapshot.manifestOf(namespace)?.requires)) {
                    by.getOrPut(requirement, ::ArrayList) += namespace
                }
            }
            val order = { r: Requirement ->
                when (r) {
                    Moderation -> 0
                    Db -> 1
                    is Http -> 2
                    is Plugin -> 3
                }
            }
            return by.entries.sortedWith(compareBy({ order(it.key) }, { it.key.id })).map { (requirement, packages) ->
                RequirementUse(requirement.id, packages)
            }
        }
    }
}

/** One requirement across a project's tree ([Requirement.combined]): its [Requirement.id] and the namespaces that declare it. */
@Serializable
data class RequirementUse(val requirement: String, val packages: List<String>)
