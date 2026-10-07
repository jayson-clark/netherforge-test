import { existsSync, readdirSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { defineConfig, type DefaultTheme } from 'vitepress'
import { writeLlms, type LlmsSection } from './llms'

// The NetherForge docs site. Three kinds of page live here:
//
// - guide/      hand-written, about behaviour that exists
// - format/     the file-format spec (also read on GitHub, so plain relative links)
// - reference/  generated from packages/api/src/spec by `pnpm generate`; never edited here.
//               Its sidebar is read from the folder, so a new API page shows up
//               without touching this file.
//
// Dead links fail the build (VitePress's default); keep it that way.

const docs = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

/**
 * `owner/name` on GitHub. CI passes the real one. Pages link to the default
 * (`https://github.com/netherforge/netherforge/...`, readable on GitHub too), and
 * the build rewrites those links to the real repository.
 */
const DEFAULT_GITHUB = 'https://github.com/netherforge/netherforge'
const repo = process.env.DOCS_REPO || 'netherforge/netherforge'
const github = `https://github.com/${repo}`

/** The first `# Heading` of a Markdown file, or its file name. */
function titleOf(file: string): string {
  const text = readFileSync(path.join(docs, file), 'utf8')
  return text.match(/^# (.+)$/m)?.[1].trim() ?? path.basename(file, '.md')
}

function link(file: string): string {
  return '/' + file.replace(/(^|\/)index\.md$/, '$1').replace(/\.md$/, '')
}

/**
 * The generated API reference, in the order its own index lists it
 * (`- [Title](file.md): …`), then anything the index doesn't mention.
 */
function referenceSidebar(): DefaultTheme.SidebarItem[] {
  const folder = path.join(docs, 'reference')
  if (!existsSync(folder)) return []
  const files = readdirSync(folder)
    .filter((f) => f.endsWith('.md'))
    .sort()
  const listed: { file: string; text: string }[] = []
  if (files.includes('index.md')) {
    const index = readFileSync(path.join(folder, 'index.md'), 'utf8')
    for (const [, text, file] of index.matchAll(/^- \[([^\]]+)\]\(([\w-]+\.md)\)/gm)) {
      if (files.includes(file)) listed.push({ file, text })
    }
  }
  const rest = files
    .filter((f) => f !== 'index.md' && !listed.some((l) => l.file === f))
    .map((file) => ({ file, text: titleOf(`reference/${file}`) }))
  return [
    ...(files.includes('index.md') ? [{ text: 'Overview', link: '/reference/' }] : []),
    ...[...listed, ...rest].map(({ file, text }) => ({ text, link: link(`reference/${file}`) })),
  ]
}

/** The format spec: the project page first, then the resource kinds in the order a project lists them. */
function formatSidebar(): DefaultTheme.SidebarItem[] {
  const order = [
    'project.md',
    'references.md',
    'packages.md',
    'settings.md',
    'centity.md',
    'menu.md',
    'dialog.md',
    'particle-effect.md',
    'cutscene.md',
    'terrain.md',
    'biome.md',
    'datapack.md',
    'dimension-type.md',
    'resource-pack.md',
  ]
  const files = readdirSync(path.join(docs, 'format')).filter((f) => f.endsWith('.md'))
  const rank = (f: string) => (order.includes(f) ? order.indexOf(f) : order.length)
  return files
    .sort((a, b) => rank(a) - rank(b) || a.localeCompare(b))
    .map((file) => ({ text: titleOf(`format/${file}`), link: link(`format/${file}`) }))
}

const guide: DefaultTheme.SidebarItem[] = [
  {
    text: 'Getting started',
    items: [
      { text: 'What is NetherForge?', link: '/guide/' },
      { text: 'Install the editor', link: '/guide/install' },
      { text: 'Your first project', link: '/guide/first-project' },
      { text: 'Your first centity', link: '/guide/first-centity' },
      { text: 'Your first script', link: '/guide/first-script' },
    ],
  },
  {
    text: 'Guides',
    items: [
      { text: 'Centities and animation', link: '/guide/centities' },
      { text: 'Scripting basics', link: '/guide/scripting' },
      { text: 'Modules and commands', link: '/guide/modules' },
      { text: 'Menus', link: '/guide/menus' },
      { text: 'Dialogs', link: '/guide/dialogs' },
      { text: 'The world', link: '/guide/world' },
      { text: 'Players, entities and inventories', link: '/guide/players-and-entities' },
      { text: 'Particle effects', link: '/guide/particles' },
      { text: 'Cutscenes', link: '/guide/cutscenes' },
      { text: 'Resource packs', link: '/guide/resource-packs' },
      { text: 'Custom blocks', link: '/guide/blocks' },
      { text: 'World generation', link: '/guide/world-generation' },
      { text: 'Physics', link: '/guide/physics' },
      { text: 'Testing your scripts', link: '/guide/testing' },
      { text: 'Starting from a template', link: '/guide/templates' },
      { text: 'A whole game: Lumen Vale', link: '/guide/showcase' },
      { text: 'Hot reload and the dev loop', link: '/guide/dev-loop' },
      { text: 'Debugging scripts', link: '/guide/debugging' },
      { text: 'Using git with a project', link: '/guide/git' },
      { text: 'Deploying to a server', link: '/guide/deploying' },
      { text: 'Editing outside the editor', link: '/guide/external-editors' },
      { text: 'Using coding agents', link: '/guide/agents' },
    ],
  },
]

/** `/guide/` → `guide/index.md`, `/guide/install` → `guide/install.md`. */
function fileOf(link: string): string {
  const relative = link.replace(/^\//, '')
  return relative === '' || relative.endsWith('/') ? `${relative}index.md` : `${relative}.md`
}

/** The pages of sidebar groups, in order. */
function pagesOf(items: DefaultTheme.SidebarItem[]): LlmsSection['pages'] {
  return items.flatMap((item) => [
    ...(item.link ? [{ file: fileOf(item.link), title: item.text ?? item.link }] : []),
    ...pagesOf(item.items ?? []),
  ])
}

const title = 'NetherForge'
const description =
  'A desktop editor and Paper plugin for Minecraft server content: scripted entities, Lua modules, menus, dialogs and resource packs.'
const base = process.env.DOCS_BASE || '/'

export default defineConfig({
  title,
  description,
  lang: 'en-US',
  base,
  cleanUrls: true,
  head: [['link', { rel: 'icon', type: 'image/svg+xml', href: `${base}logo.svg` }]],

  themeConfig: {
    logo: '/logo.svg',
    nav: [
      { text: 'Guide', link: '/guide/', activeMatch: '^/guide/' },
      { text: 'File format', link: '/format/project', activeMatch: '^/format/' },
      { text: 'Lua API', link: '/reference/', activeMatch: '^/reference/' },
      { text: 'Contributing', link: '/contributing' },
      { text: 'Download', link: `${github}/releases/latest` },
    ],
    sidebar: {
      '/guide/': guide,
      '/format/': [{ text: 'File format', items: formatSidebar() }],
      '/reference/': [{ text: 'Lua API reference', items: referenceSidebar() }],
      '/contributing': guide,
    },
    outline: [2, 3],
    search: { provider: 'local' },
    socialLinks: [{ icon: 'github', link: github }],
    editLink: {
      pattern: `${github}/edit/main/docs/:path`,
      text: 'Edit this page on GitHub',
    },
    footer: {
      message:
        'Apache-2.0. Not an official Minecraft product; not approved by or associated with Mojang or Microsoft.',
    },
  },

  // llms.txt, llms-full.txt and every page's Markdown, for AI tools (see llms.ts).
  // DOCS_URL makes its links absolute, as llms.txt readers expect.
  buildEnd(site) {
    writeLlms({
      docs,
      outDir: site.outDir,
      base: (process.env.DOCS_URL || base).replace(/\/?$/, '/'),
      title,
      description,
      sections: [
        { title: 'Guide', pages: pagesOf(guide) },
        { title: 'File format', pages: pagesOf(formatSidebar()) },
        { title: 'Lua API reference', pages: pagesOf(referenceSidebar()) },
      ],
    })
  },

  // Reference pages are generated from packages/api/src/spec: editing them here would be
  // overwritten, so they get no edit link.
  transformPageData(pageData) {
    if (pageData.relativePath.startsWith('reference/')) {
      pageData.frontmatter.editLink = false
    }
  },

  markdown: {
    theme: { light: 'github-light', dark: 'github-dark' },
    config(md) {
      const render = md.renderer.rules.link_open
      md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
        const href = tokens[idx].attrGet('href')
        if (href?.startsWith(DEFAULT_GITHUB)) {
          tokens[idx].attrSet('href', github + href.slice(DEFAULT_GITHUB.length))
        }
        return render
          ? render(tokens, idx, options, env, self)
          : self.renderToken(tokens, idx, options)
      }
    },
  },
})
