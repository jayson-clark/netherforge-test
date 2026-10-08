import { describe, expect, it, vi } from 'vitest'
import { MemoryBackend } from '@/core/backend/memory'
import { MANIFEST_FILE, newProjectFiles } from '@/core/format'
import { TEMPLATES, templateLocation } from '@/core/templates'
import { createWorkspace } from './workspace'

const ROOT = '/memory/fresh'
/**
 * Until the store's revalidation has read [namespace]'s package. Reading one hashes it
 * (WebCrypto, off the JS thread), so it's waited for as state, not on a clock.
 */
const packageLoaded = (
  ws: () => ReturnType<ReturnType<typeof createWorkspace>['getState']>,
  namespace: string,
) => vi.waitFor(() => expect(ws().outline?.packages?.[namespace]).toBeDefined(), { timeout: 5000 })

async function openFresh() {
  const backend = new MemoryBackend({ projects: { [ROOT]: newProjectFiles('Fresh', '26.3') } })
  const store = createWorkspace(backend)
  await store.getState().openProject(ROOT)
  return { backend, store, ws: () => store.getState() }
}

describe('the project templates', () => {
  it('are the packages the repository tests, without their tests', () => {
    expect(TEMPLATES.map((it) => it.id)).toEqual(['minigame', 'shop', 'rpg_mob'])
    for (const template of TEMPLATES) {
      expect(Object.keys(template.files)).toContain('netherforge.json')
      expect(Object.keys(template.files).filter((it) => it.startsWith('tests/'))).toEqual([])
      expect(JSON.parse(template.files['netherforge.json']!)).toMatchObject({
        namespace: template.namespace,
      })
    }
  })

  it.each(TEMPLATES)(
    '$title comes into a project as a dependency that validates clean',
    async (template) => {
      const { backend, ws } = await openFresh()
      expect(await ws().addTemplate(template.id)).toBe(true)
      await packageLoaded(ws, template.namespace)

      const files = backend.testFiles()
      for (const path of Object.keys(template.files)) {
        expect(files[`${templateLocation(template)}/${path}`]).toBe(template.files[path])
      }
      const manifest = JSON.parse(files[MANIFEST_FILE] as string) as {
        dependencies: Record<string, unknown>
      }
      expect(manifest.dependencies).toEqual({
        [template.namespace]: { path: templateLocation(template) },
      })
      expect(ws().docs[MANIFEST_FILE]?.dirty).toBe(false)
      await vi.waitFor(() => expect(ws().problems).toEqual([]), { timeout: 5000 })
      expect(ws().outline?.packages?.[template.namespace]?.location).toBe(
        templateLocation(template),
      )
      // Nothing of it is the project's own: the explorer's kinds stay empty.
      expect(ws().files.filter((it) => it.startsWith('items/'))).toEqual([])
    },
  )

  it('lets a resource be copied in, and a template be added once', async () => {
    const { backend, ws } = await openFresh()
    expect(await ws().addTemplate('shop')).toBe(true)
    await packageLoaded(ws, 'template_shop')
    expect(await ws().addTemplate('shop')).toBe(false)
    expect(ws().notice?.text).toContain('already uses template_shop')

    expect(await ws().copyFromPackage('template_shop', 'item', 'lucky_charm', 'charm')).toBe(true)
    expect(backend.testFiles()['items/charm/item.json']).toContain('Lucky charm')
    // The menu names the charm bare, as the package's own: copied, it names the package's.
    expect(await ws().copyFromPackage('template_shop', 'menu', 'shop', 'shop')).toBe(true)
    expect(backend.testFiles()['menus/shop/menu.json']).toContain('template_shop:lucky_charm')
    await vi.waitFor(() => expect(ws().problems).toEqual([]), { timeout: 5000 })
  })

  it('is taken away again by undo', async () => {
    const { backend, ws } = await openFresh()
    await ws().addTemplate('minigame')
    await ws().undo(MANIFEST_FILE)
    expect(Object.keys(backend.testFiles()).filter((it) => it.startsWith('templates/'))).toEqual([])
    expect(JSON.parse(backend.testFiles()[MANIFEST_FILE]!).dependencies).toBeUndefined()
  })
})
