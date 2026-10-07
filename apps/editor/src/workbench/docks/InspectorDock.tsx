/**
 * The right dock: whatever the active editor portals into it with
 * `InspectorPanel`. It stays mounted while hidden, so an editor's inspector
 * always has somewhere to go (and the toggle keeps its scroll position).
 * A read-only document's inspector is disabled, as its view is.
 */
import { PanelHeader } from '@/ui/layout'
import { IconButton } from '@/ui/Button'
import { useApp, useWorkspace } from '@/state/providers'
import { readOnlyReason } from '@/core/store/project'
import styles from './Docks.module.css'

export function InspectorDock({
  target,
  hidden,
}: {
  target: (element: HTMLFieldSetElement | null) => void
  hidden: boolean
}) {
  const { layout } = useApp()
  const readOnly = useWorkspace((s) => {
    const path = s.tabs.find((it) => it.id === s.activeTab)?.path
    return path !== undefined && readOnlyReason(s.readOnly, path) !== null
  })
  return (
    <section className={hidden ? styles.hidden : styles.dock} aria-label="Inspector dock">
      <PanelHeader
        title="Inspector"
        actions={
          <IconButton
            icon="close"
            size={12}
            label="Hide the inspector"
            onClick={() => layout.getState().set({ inspectorOpen: false })}
          />
        }
      />
      <fieldset ref={target} className={styles.target} disabled={readOnly} />
      <p className={styles.empty}>Nothing to inspect here.</p>
    </section>
  )
}
