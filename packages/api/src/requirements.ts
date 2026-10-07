/**
 * What a function's `requires` means (`Fn.requires`): each requirement, and how a package
 * declares it in `netherforge.json`'s `requires`. The generator writes the declaration into the
 * docs and stubs of every function that needs it; the runtime checks it at the call, held to the
 * package whose code makes it (`Requirements.kt`, over format's `Requirement`); the editor flags
 * a call in a project that hasn't declared it (`granted`).
 *
 * Format's `Requirement` (`packages/format/.../project/Requirement.kt`) is the model the runtime
 * and the editor's requirements view read; `granted` here says the same for the stubs.
 */
import type { Requirement } from './types.ts'

/** A requirement's kind: the word before any `:` (`plugin` for `plugin:vault`). */
export type RequirementKind = 'moderation' | 'http' | 'db' | 'plugin'

export interface RequirementSpec {
  /** What it lets scripts do, as a phrase: `ban players and change the server's settings`. */
  what: string
  /** How a package declares it, in Markdown: `"requires": { "moderation": true }` in `netherforge.json`. */
  declared: (requirement: Requirement) => string
}

export const REQUIREMENTS: Record<RequirementKind, RequirementSpec> = {
  moderation: {
    what: 'ban and unban players, change the whitelist and the server settings players see',
    declared: () => '`"requires": { "moderation": true }` in `netherforge.json`',
  },
  http: {
    what: 'make requests to other servers, only to the hosts the package lists',
    declared: () => 'each host it calls in `"requires": { "http": [...] }` in `netherforge.json`',
  },
  db: {
    what: "keep a database of the package's own",
    declared: () => '`"requires": { "db": true }` in `netherforge.json`',
  },
  plugin: {
    what: 'use another plugin on the server',
    declared: (requirement) =>
      `\`"requires": { "plugins": ["${requirement.slice('plugin:'.length)}"] }\` in \`netherforge.json\``,
  },
}

/** A plugin requirement's plugin name: lowercase letters, digits, `_` and `-` (`plugin:vault`), as format's `Names.PLUGIN_NAME`. */
const PLUGIN = /^plugin:[a-z0-9_-]{1,64}$/

/** The kind of [requirement], or null for text that isn't one. */
export function requirementKind(requirement: string): RequirementKind | null {
  if (PLUGIN.test(requirement)) return 'plugin'
  return requirement in REQUIREMENTS && requirement !== 'plugin'
    ? (requirement as RequirementKind)
    : null
}

/**
 * The sentence a function that needs [requirement] carries in its docs and stubs. Throws for
 * text that isn't a requirement.
 */
export function requirementSentence(requirement: Requirement, where: string): string {
  const kind = requirementKind(requirement)
  if (!kind) throw new Error(`${where}: "${requirement}" isn't a requirement`)
  return `Needs ${REQUIREMENTS[kind].declared(requirement)}: without it, calling it is an error.`
}

/** The part of `netherforge.json` a package declares its requirements in: `requires`. */
export interface DeclaringManifest {
  requires?: {
    moderation?: boolean | null
    db?: boolean | null
    http?: string[] | null
    plugins?: string[] | null
  } | null
}

/**
 * What [manifest] declares, as the spec's `requires` names it: what scripts of the package it
 * belongs to may use. `http` once it lists any host (each request then checks its own); the
 * runtime's `Requirement.grantedBy` says the same.
 */
export function granted(manifest: DeclaringManifest | null | undefined): Set<Requirement> {
  const out = new Set<Requirement>()
  const requires = manifest?.requires
  if (!requires) return out
  if (requires.moderation === true) out.add('moderation')
  if (requires.db === true) out.add('db')
  if ((requires.http ?? []).length > 0) out.add('http')
  for (const name of requires.plugins ?? []) out.add(`plugin:${name}`)
  return out
}
