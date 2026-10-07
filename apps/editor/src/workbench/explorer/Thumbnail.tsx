/**
 * What a resource looks like in the explorer, the palette, the outline's
 * header and its tab: its kind's thumbnail (a project item's icon, a
 * recipe's result, a pack's first picture, a centity drawn), over or instead
 * of the kind's icon.
 */
import { Suspense } from 'react'
import { cx } from '@/ui/cx'
import { Icon } from '@/ui/Icon'
import type { ExplorerFolder } from './folders'
import styles from './Thumbnail.module.css'

export function Thumbnail({
  folder,
  id,
  size,
}: {
  folder: ExplorerFolder
  id: string
  size: number
}) {
  const picture = Math.round(size * 0.62)
  const icon = <KindIcon folder={folder} size={picture} />
  const Picture = folder.contribution.thumbnail
  return (
    <span className={styles.thumb} aria-hidden="true">
      {Picture ? (
        <Suspense fallback={icon}>
          <Picture id={id} size={picture} fallback={icon} />
        </Suspense>
      ) : (
        icon
      )}
    </span>
  )
}

const toneOf = (folder: ExplorerFolder) => {
  const tone = folder.contribution.tone
  return tone && styles[tone]
}

/** The kind's own small icon, in its colour (the explorer's folder list). */
export function FolderIcon({ folder }: { folder: ExplorerFolder }) {
  return <Icon name={folder.icon} className={cx(styles.icon, toneOf(folder))} />
}

function KindIcon({ folder, size }: { folder: ExplorerFolder; size: number }) {
  return (
    <Icon
      name={folder.icon}
      size={Math.max(16, Math.round(size * 0.7))}
      className={cx(styles.icon, toneOf(folder))}
    />
  )
}
