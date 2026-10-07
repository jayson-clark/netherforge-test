/**
 * What an item stack looks like in a slot, resolved without drawing it:
 * - a pack item model (`itemModel` naming one of the project's packs): its
 *   GUI picture (`guiTexture`, else `texture`), a project file, or for one
 *   drawn as a pack block (`block`), that look's faces as a cube;
 * - else the client's own model for the item kind: a flat sprite (layers of
 *   textures) or a cuboid model to render (blocks, and anything with elements);
 * - else nothing, and the caller draws a placeholder.
 *
 * A pack reference that names nothing falls back to the vanilla look, which
 * is what the client does too.
 */
import type { ItemDef, ResourcePackFile, ResourcePacksPreview } from '@/core/format'
import { lookOf, type BlockFace } from '@/minecraft/block/look'
import {
  isSpriteModel,
  itemModelRef,
  resolveModel,
  spriteLayers,
  type AssetLoader,
} from '@/minecraft/client/model'
import { resourcePackTexturePath } from '@/core/paths'
import { splitResourcePackRef } from './item'

export type IconSource =
  | { kind: 'project'; path: string }
  | { kind: 'block'; faces: Record<BlockFace, string> }
  | { kind: 'sprite'; textures: string[] }
  | { kind: 'model'; item: string }
  | { kind: 'none' }

/**
 * What an `itemModel` reference draws in a GUI from the project's packs: a
 * picture (a project path, or a package path for a package's pack), a block
 * look's faces, or null. [namespace] is the one the reference is written in,
 * [home] the project's (the packs' keys); [compiled] gives a block look's faces.
 */
export function resourcePackItemLook(
  itemModel: string | undefined,
  packs: Record<string, ResourcePackFile>,
  compiled: ResourcePacksPreview,
  namespace: string,
  home: string = namespace,
): Extract<IconSource, { kind: 'project' | 'block' }> | null {
  const ref = splitResourcePackRef('item_model', itemModel, namespace, home)
  if (!ref) return null
  const def = packs[ref[0]]?.items?.[ref[1]]
  if (!def) return null
  const picture = def.guiTexture ?? def.texture
  if (picture) return { kind: 'project', path: resourcePackTexturePath(ref[0], picture) }
  // The pack's own references are written in its namespace: a package's pack is keyed `ns:id`.
  const colon = ref[0].indexOf(':')
  const faces = lookOf(compiled, def.block, colon < 0 ? home : ref[0].slice(0, colon), home)?.faces
  return faces ? { kind: 'block', faces } : null
}

export async function iconSource(
  item: Pick<ItemDef, 'kind' | 'itemModel'>,
  packs: Record<string, ResourcePackFile>,
  compiled: ResourcePacksPreview,
  namespace: string,
  assets: AssetLoader | null,
  home: string = namespace,
): Promise<IconSource> {
  const look = resourcePackItemLook(item.itemModel, packs, compiled, namespace, home)
  if (look) return look
  if (!assets || !item.kind) return { kind: 'none' }
  const model = await resolveModel(assets, await itemModelRef(assets, item.kind))
  if (!model) return { kind: 'none' }
  if (isSpriteModel(model)) {
    const textures = spriteLayers(model)
    return textures.length > 0 ? { kind: 'sprite', textures } : { kind: 'none' }
  }
  return model.elements.length > 0 ? { kind: 'model', item: item.kind } : { kind: 'none' }
}
