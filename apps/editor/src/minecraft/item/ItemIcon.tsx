/**
 * An item stack as a slot shows it: its picture (a pack's item model, the
 * client's sprite, or a 3D model rendered the way the GUI draws it: the
 * client's, or a pack block's cube), the
 * enchantment shimmer, and the count. Without client assets it's a labelled
 * placeholder, never an error.
 */
import { useEffect, useState } from 'react'
import type { ItemDef } from '@/core/format'
import { blockLookPicture, itemPicture } from '@/minecraft/client/iconRender'
import { useClientAssets } from '@/minecraft/client/useClientAssets'
import {
  useCompiledResourcePacks,
  useHomeNamespace,
  useProjectFileUrl,
  useResourcePackFiles,
  useReferenceNamespace,
} from '@/state/useResourcePacks'
import { cx } from '@/ui/cx'
import { iconSource, type IconSource } from './icon'
import styles from './ItemIcon.module.css'
import { hasGlint, itemLabel, stackLook } from './item'
import { useProjectItem } from './projectItems'

type Picture = { layers: string[] } | { label: string }

function useItemPicture(item: Pick<ItemDef, 'kind' | 'itemModel'>): Picture {
  const packs = useResourcePackFiles()
  const compiled = useCompiledResourcePacks()
  const namespace = useReferenceNamespace()
  const home = useHomeNamespace()
  const assets = useClientAssets()
  const projectUrl = useProjectFileUrl()
  const [state, setState] = useState<{
    key: string
    source: IconSource
    rendered?: string | null
  }>()
  const key = `${item.kind}|${item.itemModel ?? ''}|${assets?.version ?? ''}|${namespace}|${home}|${JSON.stringify(packs)}|${JSON.stringify(compiled.resourcePacks)}`

  useEffect(() => {
    let live = true
    void iconSource(item, packs, compiled, namespace, assets, home).then(async (source) => {
      if (!live) return
      setState({ key, source })
      if (source.kind === 'model' && assets) {
        const rendered = await itemPicture(assets, source.item)
        if (live) setState({ key, source, rendered })
      }
      if (source.kind === 'block' && assets) {
        const urls = Object.fromEntries(
          Object.entries(source.faces).map(([face, path]) => [face, projectUrl(path)]),
        )
        const rendered = await blockLookPicture(assets, urls)
        if (live) setState({ key, source, rendered })
      }
    })
    return () => {
      live = false
    }
    // `key` covers item, packs and assets.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key])

  const label = itemLabel(item.kind || '?')
  if (!state || state.key !== key) return { label }
  const { source } = state
  switch (source.kind) {
    case 'project':
      return { layers: [projectUrl(source.path)] }
    case 'sprite':
      return assets ? { layers: source.textures.map((it) => assets.textureUrl(it)) } : { label }
    case 'model':
    case 'block':
      return state.rendered ? { layers: [state.rendered] } : { label }
    case 'none':
      return { label }
  }
}

export function ItemIcon({ item: stack, size = 32 }: { item: ItemDef; size?: number }) {
  // A project item's stack looks like its definition, with the stack's own fields on top.
  const item = stackLook(stack, useProjectItem(stack.item))
  const picture = useItemPicture(item)
  const count = item.count ?? 1
  return (
    <span
      className={cx(styles.icon, hasGlint(item) && styles.glint)}
      style={{ width: size, height: size }}
    >
      {'layers' in picture ? (
        picture.layers.map((url, index) => (
          <img key={index} src={url} alt="" draggable={false} className={styles.layer} />
        ))
      ) : (
        <span className={styles.placeholder} style={{ fontSize: Math.max(8, size / 4) }}>
          {picture.label
            .split(' ')
            .map((word) => word[0])
            .join('')
            .slice(0, 3)
            .toUpperCase()}
        </span>
      )}
      {count > 1 && <span className={styles.count}>{count}</span>}
    </span>
  )
}
