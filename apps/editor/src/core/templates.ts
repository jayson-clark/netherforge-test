/**
 * The project templates: "minigame", "shop" and "RPG mob", each a small
 * package (`examples/template_<id>`) the editor can put into a project.
 * They are ordinary packages, the same files the repository's tests run (the
 * test runner runs each one's `tests/`, so an API change that breaks one
 * fails CI), bundled here at build time. Adding one writes it into the
 * project as a folder, `templates/<namespace>/`, and makes it a dependency
 * (`{ "path": "templates/<namespace>" }`), so its resources appear under the
 * explorer's Dependencies, each with **Copy into project**.
 */

export interface ProjectTemplate {
  /** `minigame`, `shop`, `rpg_mob`: the example's folder is `template_<id>`. */
  id: string
  /** The package's namespace, which is also its folder under `templates/`. */
  namespace: string
  title: string
  /** What it is and what it shows, in a line. */
  summary: string
  /** The package's files by path inside it: what makes up its resources, no tests. */
  files: Record<string, string>
}

/** Where a template is written in a project, and the location its dependency names. */
export const TEMPLATES_FOLDER = 'templates'
export const templateLocation = (template: Pick<ProjectTemplate, 'namespace'>) =>
  `${TEMPLATES_FOLDER}/${template.namespace}`

// What a template's .gitignore keeps out of the repo (the editor's own state, and the default font's
// advances it writes from an imported client) stays out of the bundle too.
const sources = import.meta.glob(
  [
    '../../../../examples/template_*/**/*',
    '!../../../../examples/template_*/tests/**',
    '!../../../../examples/template_*/.netherforge/**',
    '!../../../../examples/template_*/.gitignore',
    '!../../../../examples/template_*/fonts/default.json',
  ],
  { query: '?raw', import: 'default', eager: true },
) as Record<string, string>

const PREFIX = '../../../../examples/template_'

/** What each says about itself: the rest (the files) comes from the example. */
const ABOUT: Record<string, Pick<ProjectTemplate, 'title' | 'summary'>> = {
  minigame: {
    title: 'Minigame',
    summary:
      'A lobby, a countdown and timed rounds between two teams, with a sidebar and boss bar.',
  },
  shop: {
    title: 'Shop',
    summary: 'A menu that sells items for coins kept per player in a database.',
  },
  rpg_mob: {
    title: 'RPG mob',
    summary: 'A goblin that spawns by itself, can be hurt and killed, and drops loot.',
  },
}

function filesOf(id: string): Record<string, string> {
  const prefix = `${PREFIX}${id}/`
  return Object.fromEntries(
    Object.entries(sources)
      .filter(([path]) => path.startsWith(prefix))
      // `.luarc.json` is the example's own LuaLS setup; the project has its own.
      .filter(([path]) => !path.endsWith('/.luarc.json'))
      .map(([path, text]) => [path.slice(prefix.length), text] as const)
      .sort(([a], [b]) => a.localeCompare(b)),
  )
}

export const TEMPLATES: ProjectTemplate[] = Object.entries(ABOUT).map(([id, about]) => ({
  id,
  namespace: `template_${id}`,
  ...about,
  files: filesOf(id),
}))

export const templateById = (id: string) => TEMPLATES.find((it) => it.id === id)
