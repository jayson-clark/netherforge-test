import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { afterEach, describe, expect, it } from 'vitest'
import { Tabs } from './Tabs'

afterEach(cleanup)

type Panel = 'problems' | 'console' | 'instances'

function Harness() {
  const [current, setCurrent] = useState<Panel>('problems')
  return (
    <Tabs<Panel>
      label="Output panels"
      current={current}
      onSelect={setCurrent}
      tabs={[
        { id: 'problems', label: 'Problems 3', name: 'Problems' },
        { id: 'console', label: 'Console' },
        { id: 'instances', label: 'Instances' },
      ]}
    >
      <p>{current} panel</p>
    </Tabs>
  )
}

describe('Tabs', () => {
  it('moves between tabs with the arrow keys, Home and End, and labels the panel by its tab', async () => {
    const user = userEvent.setup()
    render(<Harness />)
    const tab = (name: string) => screen.getByRole('tab', { name })
    expect(tab('Problems').getAttribute('aria-selected')).toBe('true')
    expect(screen.getByRole('tabpanel', { name: 'Problems' }).textContent).toBe('problems panel')

    await user.click(tab('Problems'))
    await user.keyboard('{ArrowRight}')
    expect(tab('Console').getAttribute('aria-selected')).toBe('true')
    expect(document.activeElement).toBe(tab('Console'))
    expect(screen.getByRole('tabpanel').textContent).toBe('console panel')
    await user.keyboard('{End}')
    expect(tab('Instances').getAttribute('aria-selected')).toBe('true')
    // Wraps round.
    await user.keyboard('{ArrowRight}')
    expect(tab('Problems').getAttribute('aria-selected')).toBe('true')
    await user.keyboard('{ArrowLeft}')
    expect(tab('Instances').getAttribute('aria-selected')).toBe('true')
    await user.keyboard('{Home}')
    expect(tab('Problems').getAttribute('aria-selected')).toBe('true')
  })
})
