/** The dev server's output and the editor's own lines, filterable, with links to sources and a command line. */
import { useEffect, useRef, useState, type ReactNode } from 'react'
import { findSourceRefs } from '@/core/paths'
import type { ConsoleLine } from '@/core/store/run'
import { useApp, useRun } from '@/state/providers'
import { Button } from '@/ui/Button'
import { cx } from '@/ui/cx'
import { Bar } from '@/ui/layout'
import styles from './Console.module.css'

export function ConsolePanel() {
  const { workspace, run } = useApp()
  // The buffer changes in place; `lines()` is the same array until the next change, a new one after.
  const lines = useRun((s) => s.console.lines())
  const connected = useRun((s) => s.server.phase === 'running')
  const [filter, setFilter] = useState('')
  const [command, setCommand] = useState('')
  const end = useRef<HTMLDivElement>(null)
  const shown = filter
    ? lines.filter((line) => line.text.toLowerCase().includes(filter.toLowerCase()))
    : lines

  useEffect(() => {
    end.current?.scrollIntoView?.({ block: 'end' })
  }, [shown.length])

  const openSource = (file: string, line?: number) =>
    void workspace.getState().openFile(file, { line: line ?? 1 })

  return (
    <div className={styles.console}>
      <Bar>
        <input
          aria-label="Filter console"
          placeholder="Filter"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
        />
        <Button size="small" onClick={() => run.getState().clearConsole()}>
          Clear
        </Button>
      </Bar>
      <div className={styles.lines} role="log" aria-label="Console">
        {shown.map((line) => (
          <ConsoleRow key={line.id} line={line} openSource={openSource} />
        ))}
        <div ref={end} />
      </div>
      <form
        className={styles.input}
        onSubmit={(event) => {
          event.preventDefault()
          void run.getState().command(command)
          setCommand('')
        }}
      >
        <span aria-hidden="true">&gt;</span>
        <input
          aria-label="Server command"
          placeholder={connected ? 'Server command' : 'Start the server to run commands'}
          disabled={!connected}
          value={command}
          onChange={(e) => setCommand(e.target.value)}
        />
      </form>
    </div>
  )
}

function ConsoleRow({
  line,
  openSource,
}: {
  line: ConsoleLine
  openSource: (file: string, line?: number) => void
}) {
  return (
    <div
      className={cx(
        styles.line,
        line.kind === 'editor' && styles.editor,
        line.level === 'warn' && styles.warn,
        line.level === 'error' && styles.error,
      )}
    >
      {line.source && (
        <button
          type="button"
          className={styles.sourceLink}
          onClick={() => openSource(line.source!.file, line.source!.line)}
          aria-label={`Open ${line.source.file}${line.source.line ? `:${line.source.line}` : ''}`}
        >
          {line.source.file}
          {line.source.line ? `:${line.source.line}` : ''}
        </button>
      )}
      <Linkified text={line.text} openSource={openSource} />
      {line.detail && <pre className={styles.traceback}>{line.detail}</pre>}
    </div>
  )
}

/** Turns `centities/x/y.lua:12` in output into links. */
function Linkified({
  text,
  openSource,
}: {
  text: string
  openSource: (file: string, line?: number) => void
}) {
  const refs = findSourceRefs(text)
  if (refs.length === 0) return <span>{text}</span>
  const parts: ReactNode[] = []
  let at = 0
  refs.forEach((ref, i) => {
    if (ref.start > at) parts.push(text.slice(at, ref.start))
    parts.push(
      <button
        type="button"
        key={i}
        className={styles.sourceLink}
        onClick={() => openSource(ref.file, ref.line)}
      >
        {text.slice(ref.start, ref.end)}
      </button>,
    )
    at = ref.end
  })
  parts.push(text.slice(at))
  return <span>{parts}</span>
}
