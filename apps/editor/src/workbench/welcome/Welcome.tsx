/** The first screen: recent projects, open a folder, or create a project. */
import { useEffect, useId, useState } from 'react'
import { create } from 'zustand'
import type { Backend, RecentProject } from '@/core/backend/types'
import { TEMPLATES } from '@/core/templates'
import type { WorkspaceStore } from '@/core/store/workspace'
import { useApp, useWorkspace } from '@/state/providers'
import { Button } from '@/ui/Button'
import { FormActions, FormError, FormLabel } from '@/ui/fields'
import { Logo } from '@/ui/Logo'
import { Hint } from '@/ui/text'
import { BareTitleBar } from '@/workbench/toolbar/TitleBar'
import styles from './Welcome.module.css'

/**
 * What the welcome screen shows besides the recent list, kept outside it so
 * the File menu can ask for the create form or report a project that
 * wouldn't open.
 */
export const useWelcome = create<{ creating: boolean; error: string | null }>(() => ({
  creating: false,
  error: null,
}))

/** Opens [root], showing why on the welcome screen if it won't open. */
export async function openProjectAt(workspace: WorkspaceStore, root: string) {
  useWelcome.setState({ error: null })
  try {
    await workspace.getState().openProject(root)
    useWelcome.setState({ creating: false })
  } catch (e) {
    useWelcome.setState({ error: e instanceof Error ? e.message : String(e) })
  }
}

/** Asks for a project folder and opens it. */
async function pickAndOpenProject(backend: Backend, workspace: WorkspaceStore) {
  const root = await backend.pickFolder('Open a NetherForge project')
  if (root) await openProjectAt(workspace, root)
}

export function Welcome() {
  const { backend, workspace } = useApp()
  const appInfo = useWorkspace((s) => s.appInfo)
  const [recent, setRecent] = useState<RecentProject[] | null>(null)
  const creating = useWelcome((s) => s.creating)
  const error = useWelcome((s) => s.error)
  const setCreating = (creating: boolean) => useWelcome.setState({ creating })

  useEffect(() => {
    backend.recentProjects().then(setRecent, () => setRecent([]))
  }, [backend])

  const open = (root: string) => openProjectAt(workspace, root)
  const openFolder = () => pickAndOpenProject(backend, workspace)

  return (
    <main className={styles.welcome}>
      <BareTitleBar />
      <div className={styles.card}>
        <h1 className={styles.title}>
          <Logo size={24} /> NetherForge
        </h1>
        <Hint>Build centities and scripts for your Minecraft server.</Hint>
        {error && <FormError role="alert">{error}</FormError>}
        {creating ? (
          <CreateProject
            onCancel={() => setCreating(false)}
            versions={appInfo?.minecraftVersions ?? []}
          />
        ) : (
          <>
            <div className={styles.actions}>
              <Button variant="primary" icon="folder" onClick={() => void openFolder()}>
                Open folder…
              </Button>
              <Button icon="plus" onClick={() => setCreating(true)}>
                Create project…
              </Button>
            </div>
            <h2 className={styles.heading}>Recent projects</h2>
            {recent === null ? null : recent.length === 0 ? (
              <Hint>Nothing yet. Open a folder with a netherforge.json, or create a project.</Hint>
            ) : (
              <ul className={styles.recent} aria-label="Recent projects">
                {recent.map((project) => (
                  <li key={project.root}>
                    <button
                      type="button"
                      className={styles.recentButton}
                      onClick={() => void open(project.root)}
                    >
                      <span className={styles.recentName}>{project.name}</span>
                      <span className={styles.recentRoot}>{project.root}</span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </>
        )}
        {appInfo && <p className={styles.version}>NetherForge {appInfo.version}</p>}
      </div>
    </main>
  )
}

function CreateProject({ onCancel, versions }: { onCancel: () => void; versions: string[] }) {
  const { backend, workspace } = useApp()
  const [name, setName] = useState('My server')
  const [minecraft, setMinecraft] = useState(versions[0] ?? '')
  // A template to start from ('' for an empty project): it comes into the new project as a
  // package, under the explorer's Dependencies, ready to copy from.
  const [template, setTemplate] = useState('')
  const [folder, setFolder] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const nameId = useId()
  const versionId = useId()
  const templateId = useId()

  const create = async () => {
    if (!folder) return
    setBusy(true)
    setError(null)
    try {
      await workspace.getState().createProject(folder, name.trim(), minecraft)
      if (template) await workspace.getState().addTemplate(template)
      useWelcome.setState({ creating: false })
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form
      className={styles.form}
      aria-label="Create project"
      onSubmit={(event) => {
        event.preventDefault()
        void create()
      }}
    >
      <FormLabel htmlFor={nameId}>Name</FormLabel>
      <input id={nameId} value={name} onChange={(e) => setName(e.target.value)} required />
      <FormLabel htmlFor={versionId}>Minecraft version</FormLabel>
      <select id={versionId} value={minecraft} onChange={(e) => setMinecraft(e.target.value)}>
        {versions.map((version) => (
          <option key={version} value={version}>
            {version}
          </option>
        ))}
      </select>
      <FormLabel htmlFor={templateId}>Start with</FormLabel>
      <select id={templateId} value={template} onChange={(e) => setTemplate(e.target.value)}>
        <option value="">An empty project</option>
        {TEMPLATES.map((it) => (
          <option key={it.id} value={it.id}>
            {it.title}: {it.summary}
          </option>
        ))}
      </select>
      <FormLabel>Folder</FormLabel>
      <div className={styles.folderPick}>
        <code>{folder ?? 'No folder chosen'}</code>
        <Button
          onClick={async () =>
            setFolder(
              (await backend.pickFolder('Choose an empty folder for the project')) ?? folder,
            )
          }
        >
          Choose…
        </Button>
      </div>
      {error && <FormError role="alert">{error}</FormError>}
      <FormActions>
        <Button onClick={onCancel}>Cancel</Button>
        <Button
          type="submit"
          variant="primary"
          disabled={!folder || !name.trim() || !minecraft || busy}
        >
          Create
        </Button>
      </FormActions>
    </form>
  )
}
