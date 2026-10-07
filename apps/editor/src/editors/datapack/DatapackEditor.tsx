/**
 * A datapack passed through (`datapacks/<id>/`): the game's own worldgen JSON,
 * for experts. The screen says which data pack formats the pack and each
 * overlay are for and lists its files by folder; `pack.mcmeta` is edited as
 * JSON (with its schema), and each file opens in a JSON tab of its own. What's
 * wrong with any of them is in Problems.
 */
import type { PackMeta } from '@/core/format'
import { locationOf, resourceOf } from '@/core/paths'
import { useApp, useWorkspace } from '@/state/providers'
import { EditorBar, EditorScreen, Page } from '@/editors/shared/EditorLayout'
import { EditAsJson, useModelDoc } from '@/editors/shared/modelDoc'
import { RawDocView } from '@/editors/shared/RawDocView'
import { Section } from '@/ui/fields'
import { Button } from '@/ui/Button'
import { Hint, Muted } from '@/ui/text'
import { fileGroups, rangeText } from './ops'

export function DatapackEditor({ path }: { path: string }) {
  const { workspace } = useApp()
  const { model } = useModelDoc<PackMeta>(path)
  const id = resourceOf(path)?.id ?? path
  const folder = `${locationOf('datapack', id)}/`
  const all = useWorkspace((s) => s.files)
  const files = all.filter((file) => file.startsWith(folder))
  if (!model) return <RawDocView path={path} />
  const groups = fileGroups(
    model,
    files.map((file) => file.slice(folder.length)),
  )

  return (
    <EditorScreen>
      <EditorBar title={id}>
        <EditAsJson path={path} />
      </EditorBar>
      <Page>
        <Hint>
          The game&apos;s own worldgen JSON, passed through into the server&apos;s start-up
          datapack. A server whose data pack format isn&apos;t{' '}
          {rangeText(model.pack.min_format, model.pack.max_format)} leaves it out. Saving a file
          restarts the dev server.
        </Hint>
        {groups.map((group) => (
          <Section
            key={group.folder || 'other'}
            title={
              group.folder === ''
                ? 'Not part of the pack'
                : group.folder === 'data'
                  ? `data/ (formats ${group.formats})`
                  : `Overlay ${group.folder}/ (formats ${group.formats})`
            }
          >
            {group.files.length === 0 ? (
              <Muted>No files.</Muted>
            ) : (
              <ul aria-label={`Files in ${group.folder || 'no folder of the pack'}`}>
                {group.files.map((file) => (
                  <li key={file}>
                    <Button
                      size="small"
                      variant="ghost"
                      onClick={() => void workspace.getState().openFile(`${folder}${file}`)}
                    >
                      {file}
                    </Button>
                  </li>
                ))}
              </ul>
            )}
          </Section>
        ))}
      </Page>
    </EditorScreen>
  )
}
