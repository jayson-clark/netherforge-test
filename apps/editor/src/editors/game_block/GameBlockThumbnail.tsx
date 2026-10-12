/**
 * A game block file's picture: the first few of the game's blocks it lists, as the inventory draws their items
 * (redrawn as the project's resource packs redraw them), in a collage. A `#tag` isn't drawn: the editor doesn't
 * know what's in it.
 */
import { useMemo } from 'react'
import type { GameBlockFile, ItemDef } from '@/core/format'
import { mainFileIn } from '@/core/paths'
import type { ThumbnailProps } from '@/editors/contributions'
import { ItemIcon } from '@/minecraft/item/ItemIcon'
import { currentText, parseCached } from '@/minecraft/item/projectItems'
import { useWorkspace } from '@/state/providers'
import { Collage, COLLAGE_CELLS } from '@/ui/Collage'

export function GameBlockThumbnail({ id, size, fallback }: ThumbnailProps) {
  const text = useWorkspace((s) => currentText(s, mainFileIn(s.files, 'game_block', id)))
  const blocks = parseCached<GameBlockFile>(text)?.blocks
  const items = useMemo(
    () =>
      (Array.isArray(blocks) ? blocks : [])
        .filter((it): it is string => typeof it === 'string' && it !== '' && !it.startsWith('#'))
        .slice(0, COLLAGE_CELLS)
        .map((kind): ItemDef => ({ kind })),
    [blocks],
  )
  if (items.length === 0) return <>{fallback}</>
  return (
    <Collage
      items={items}
      keyOf={(item) => item.kind!}
      render={(item, cell) => <ItemIcon item={item} size={Math.max(8, cell - 4)} />}
      size={size}
    />
  )
}
