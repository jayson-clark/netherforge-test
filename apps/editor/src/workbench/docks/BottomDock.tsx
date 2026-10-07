/**
 * The bottom dock, in two columns of the workbench's panels (`DOCK_PANELS`):
 * the project explorer and Instances on the left, Problems and the Console
 * on the right, one of each at a time. Which one shows and where the columns
 * split are remembered with the rest of the layout.
 */
import { useState } from 'react'
import { Group, Panel, usePanelRef } from 'react-resizable-panels'
import { useApp, useLayout } from '@/state/providers'
import { DEFAULT_LAYOUT, LIMITS, type DockColumn } from '@/core/store/layout'
import { IconButton } from '@/ui/Button'
import { Splitter } from '@/ui/Splitter'
import { Tabs } from '@/ui/Tabs'
import { panelsIn, shownPanel } from '../contributions'
import styles from './Docks.module.css'

const COLUMN_LABEL: Record<DockColumn, string> = {
  left: 'Project panels',
  right: 'Output panels',
}

export function BottomDock() {
  const { layout } = useApp()
  // The remembered split when the dock appears (a changed default would lay it out again).
  const [split] = useState(() => layout.getState().bottomSplit)
  const left = usePanelRef()
  const [min, max] = LIMITS.bottomSplit
  const percent = (fraction: number) => `${fraction * 100}%`

  return (
    <section className={styles.split} aria-label="Panels">
      <Group orientation="horizontal">
        <Panel
          id="left"
          style={{ overflow: 'hidden' }}
          panelRef={left}
          className={styles.column}
          defaultSize={percent(split)}
          minSize={percent(min)}
          maxSize={percent(max)}
          onResize={(size) => layout.getState().set({ bottomSplit: size.asPercentage / 100 })}
        >
          <Column column="left" />
        </Panel>
        <Splitter
          label="Resize the bottom dock's columns"
          onReset={() => left.current?.resize(percent(DEFAULT_LAYOUT.bottomSplit))}
        />
        <Panel id="right" className={styles.column} style={{ overflow: 'hidden' }}>
          <Column
            column="right"
            end={
              <IconButton
                icon="close"
                size={12}
                label="Hide the bottom dock"
                onClick={() => layout.getState().set({ bottomOpen: false })}
              />
            }
          />
        </Panel>
      </Group>
    </section>
  )
}

function Column({ column, end }: { column: DockColumn; end?: React.ReactNode }) {
  const { layout } = useApp()
  const current = useLayout((s) => (column === 'left' ? s.bottomLeftTab : s.bottomRightTab))
  const shown = shownPanel(column, current)
  const View = shown.view
  return (
    <Tabs<string>
      label={COLUMN_LABEL[column]}
      current={shown.id}
      onSelect={(next) => layout.getState().showBottom(column, next)}
      tabs={panelsIn(column).map(({ id, title, badge: Badge }) => ({
        id,
        name: title,
        label: (
          <>
            {title}
            {Badge && <Badge />}
          </>
        ),
      }))}
      end={end}
      panelClassName={styles.body}
    >
      <View />
    </Tabs>
  )
}
