/** Every problem: the editor's validation and what the dev server reported. Each opens where it is. */
import { useMemo } from 'react'
import type { Problem } from '@/core/format'
import { useApp, useRun, useWorkspace } from '@/state/providers'
import { Icon } from '@/ui/Icon'
import { Empty } from '@/ui/text'
import styles from './Problems.module.css'

/** Errors and warnings across both sources, for the tab's counts. */
export function useProblemCounts() {
  const problems = useWorkspace((s) => s.problems)
  const serverProblems = useRun((s) => s.serverProblems)
  const all = [...problems, ...serverProblems]
  const errors = all.filter((it) => it.severity === 'error').length
  return { errors, warnings: all.length - errors }
}

export function ProblemsPanel() {
  const { workspace } = useApp()
  const problems = useWorkspace((s) => s.problems)
  const serverProblems = useRun((s) => s.serverProblems)
  const all = useMemo(
    () => [
      ...problems.map((problem) => ({ problem, source: 'editor' as const })),
      ...serverProblems.map((problem) => ({ problem, source: 'server' as const })),
    ],
    [problems, serverProblems],
  )

  if (all.length === 0) return <Empty>No problems.</Empty>

  const open = (problem: Problem) =>
    void workspace.getState().openFile(problem.file, {
      line: problem.line,
      column: problem.column,
      jsonPath: problem.path,
    })

  return (
    <ul className={styles.list} aria-label="Problems">
      {all.map(({ problem, source }, index) => (
        <li key={index}>
          <button type="button" className={styles.problem} onClick={() => open(problem)}>
            <Icon
              name={problem.severity === 'error' ? 'error' : 'warning'}
              className={problem.severity === 'error' ? styles.error : styles.warning}
            />
            <span className={styles.message}>{problem.message}</span>
            <span className={styles.where}>
              {problem.file}
              {problem.line ? `:${problem.line}` : ''}
              {problem.path ? ` ${problem.path}` : ''}
              {source === 'server' ? ' · server' : ''}
            </span>
          </button>
        </li>
      ))}
    </ul>
  )
}
