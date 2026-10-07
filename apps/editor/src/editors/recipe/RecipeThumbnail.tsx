/** A recipe's picture: what it makes, as it is now (unsaved edits too). */
import type { RecipeFile } from '@/core/format'
import { mainFileOf } from '@/core/paths'
import { useWorkspace } from '@/state/providers'
import type { ThumbnailProps } from '@/editors/contributions'
import { ItemIcon } from '@/minecraft/item/ItemIcon'
import { currentText, parseCached } from '@/minecraft/item/projectItems'

export function RecipeThumbnail({ id, size, fallback }: ThumbnailProps) {
  const text = useWorkspace((s) => currentText(s, mainFileOf('recipe', id)))
  const result = parseCached<RecipeFile>(text)?.result
  return result ? <ItemIcon item={result} size={size} /> : <>{fallback}</>
}
