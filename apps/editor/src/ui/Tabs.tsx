/**
 * A row of panel tabs (the bottom dock's) over the one panel that shows:
 * react-aria's `Tabs`, so the arrow keys, Home and End move between them and
 * the panel is labelled by its tab.
 */
import type { ReactNode } from 'react'
import { Tab, TabList, TabPanel, Tabs as AriaTabs } from 'react-aria-components'
import styles from './Tabs.module.css'

export interface TabItem<T extends string> {
  id: T
  label: ReactNode
  /** The accessible name when the label has counts in it. */
  name?: string
}

export function Tabs<T extends string>({
  tabs,
  current,
  onSelect,
  label,
  end,
  panelClassName,
  children,
}: {
  tabs: TabItem<T>[]
  current: T
  onSelect: (id: T) => void
  label: string
  /** Buttons at the far end of the row. */
  end?: ReactNode
  panelClassName?: string
  /** The current tab's panel. */
  children: ReactNode
}) {
  return (
    <AriaTabs
      className={styles.root}
      selectedKey={current}
      onSelectionChange={(key) => onSelect(key as T)}
    >
      <div className={styles.row}>
        <TabList aria-label={label} className={styles.tabs}>
          {tabs.map((tab) => (
            <Tab key={tab.id} id={tab.id} aria-label={tab.name} className={styles.tab}>
              {tab.label}
            </Tab>
          ))}
        </TabList>
        {end && <div className={styles.end}>{end}</div>}
      </div>
      <TabPanel id={current} className={panelClassName}>
        {children}
      </TabPanel>
    </AriaTabs>
  )
}
