/** The project's script tests: the last run, each test with its verdict and where it failed. */
import { useApp, useTests } from '@/state/providers'
import { byFile, unpassed } from '@/core/store/tests'
import type { TestResult } from '@/core/backend/types'
import { Button } from '@/ui/Button'
import { Icon } from '@/ui/Icon'
import { Empty } from '@/ui/text'
import styles from './Tests.module.css'

/** How many tests didn't pass, for the tab's badge. */
export function useUnpassedCount() {
  return useTests((s) => unpassed(s.report).length)
}

const place = (result: TestResult) =>
  result.source
    ? `${result.source.file}${result.source.line ? `:${result.source.line}` : ''}`
    : null

export function TestsPanel() {
  const { workspace, tests } = useApp()
  const running = useTests((s) => s.running)
  const report = useTests((s) => s.report)
  const error = useTests((s) => s.error)

  const open = (result: TestResult) => {
    const file = result.source?.file ?? result.file
    void workspace.getState().openFile(file, { line: result.source?.line ?? undefined })
  }

  return (
    <div className={styles.panel}>
      <div className={styles.bar}>
        <Button icon="play" onClick={() => void tests.getState().run()} disabled={running}>
          {running ? 'Running…' : 'Run Tests'}
        </Button>
        {report && !report.error && (
          <span className={styles.summary} role="status">
            {report.passed} passed, {report.failed} failed
            {report.errored > 0 ? `, ${report.errored} errored` : ''} ({report.durationMillis} ms)
          </span>
        )}
      </div>
      {error && <p className={styles.error}>{error}</p>}
      {report?.error && (
        <div className={styles.error}>
          <p>The tests couldn&apos;t run: {report.error}</p>
          <ul>
            {report.problems.map((problem, index) => (
              <li key={index}>{problem}</li>
            ))}
          </ul>
        </div>
      )}
      {!report && !error && !running && (
        <Empty>
          No run yet. Tests are the project&apos;s <code>*_test.lua</code> files.
        </Empty>
      )}
      {report && !report.error && report.results.length === 0 && (
        <Empty>No tests found. Declare one with nf.test.case in a *_test.lua file.</Empty>
      )}
      {report &&
        byFile(report).map(({ file, results }) => (
          <section key={file} aria-label={file}>
            <h3 className={styles.file}>{file}</h3>
            <ul className={styles.list}>
              {results.map((result) => (
                <li key={result.name}>
                  <button
                    type="button"
                    className={styles.result}
                    onClick={() => open(result)}
                    aria-label={`${result.status}: ${result.name}`}
                  >
                    <Icon
                      name={result.status === 'passed' ? 'check' : 'error'}
                      className={result.status === 'passed' ? styles.passed : styles.failed}
                    />
                    <span className={styles.name}>{result.name}</span>
                    <span className={styles.where}>{result.durationMillis} ms</span>
                  </button>
                  {result.status !== 'passed' && (
                    <div className={styles.detail}>
                      <div>
                        {place(result) && <span className={styles.where}>{place(result)}: </span>}
                        {result.message}
                      </div>
                      {result.logs && result.logs.length > 0 && (
                        <pre className={styles.logs}>{result.logs.join('\n')}</pre>
                      )}
                      {result.traceback && <pre className={styles.logs}>{result.traceback}</pre>}
                    </div>
                  )}
                </li>
              ))}
            </ul>
          </section>
        ))}
    </div>
  )
}
