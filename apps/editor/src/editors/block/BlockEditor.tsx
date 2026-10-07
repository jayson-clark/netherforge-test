/**
 * A project block (`blocks/<id>/block.json`, docs/format/block.md): its cube
 * as its pack draws it, and in the inspector its look, how long it takes to
 * mine and with what, what it drops, its sounds, the centity drawn over it,
 * how often it ticks, and its one script.
 */
import { setKey } from '@/core/draft'
import {
  BlockToolValues,
  newScript,
  newSiblingFile,
  type BlockFile,
  type BlockTool,
} from '@/core/format'
import { dirname } from '@/core/paths'
import { InspectorPanel } from '@/editors/shared/docks'
import { EditorBar, EditorScreen, Stage } from '@/editors/shared/EditorLayout'
import { EditAsJson, useModelDoc } from '@/editors/shared/modelDoc'
import { RawDocView } from '@/editors/shared/RawDocView'
import { ScriptField } from '@/editors/shared/ScriptField'
import { useFocusRequests } from '@/editors/shared/useFocusRequests'
import { resourcePackRefs } from '@/minecraft/item/item'
import {
  useCompiledResourcePacks,
  useHomeNamespace,
  useReferenceNamespace,
} from '@/state/useResourcePacks'
import { useProjectFileUrl } from '@/state/useResourcePacks'
import { useWorkspace } from '@/state/providers'
import type { ProjectOutline } from '@/core/format'
import { CheckField, NumberField, SelectField, Section } from '@/ui/fields'
import { Hint, Muted } from '@/ui/text'
import { BLOCK_FACES, lookOf } from '@/minecraft/block/look'
import { setSound, soundRefs } from './ops'
import styles from './BlockEditor.module.css'

/** One empty map, so a selector returns the same value while there's no outline yet. */
const NO_RESOURCE_PACKS: NonNullable<ProjectOutline>['resourcePacks'] = {}
const NO_IDS: string[] = []

/** The choices of a reference field: none, each of [refs], and the current one when nothing has it. */
function options(refs: string[], current: string | undefined, none: string) {
  return [
    { value: '', label: none },
    ...refs.map((it) => ({ value: it, label: it })),
    ...(current && !refs.includes(current)
      ? [{ value: current, label: `${current} (missing)` }]
      : []),
  ]
}

export function BlockEditor({ path }: { path: string }) {
  const { doc, model, edit } = useModelDoc<BlockFile>(path)
  const packs = useWorkspace((s) => s.outline?.resourcePacks ?? NO_RESOURCE_PACKS)
  const lootTables = useWorkspace((s) => s.outline?.resources.loot_table ?? NO_IDS)
  const centities = useWorkspace((s) => s.outline?.resources.centity ?? NO_IDS)
  const compiled = useCompiledResourcePacks()
  const namespace = useReferenceNamespace()
  const home = useHomeNamespace()
  const url = useProjectFileUrl()
  const id = path.split('/')[1] ?? ''

  useFocusRequests(path, (segments) => segments)

  if (!doc) return null
  if (!model) return <RawDocView path={path} />

  const look = lookOf(compiled, model.model, namespace, home)
  const set = <K extends keyof BlockFile>(key: K, value: BlockFile[K] | undefined) =>
    edit((draft) => setKey(draft, key, value))
  const whole = (value: number | undefined) => (value === undefined ? undefined : Math.round(value))
  const sounds = soundRefs(packs)

  return (
    <EditorScreen>
      <EditorBar title={id}>
        <EditAsJson path={path} />
      </EditorBar>
      <Stage>
        <div className={styles.showcase} aria-label="Block preview">
          {look?.faces ? (
            <div className={styles.cube}>
              {BLOCK_FACES.map((face) => (
                <img
                  key={face}
                  className={`${styles.face} ${styles[face]}`}
                  src={url(look.faces![face])}
                  alt=""
                  data-face={face}
                />
              ))}
            </div>
          ) : (
            <div className={styles.plain} aria-label="Drawn as a note block">
              <Muted>
                {model.model
                  ? look
                    ? 'The look has no texture for some face.'
                    : `There's no look "${model.model}" in the project's packs.`
                  : 'No look yet: it is drawn as the note block it is held as.'}
              </Muted>
            </div>
          )}
          {model.centity && (
            <Muted>
              Drawn by the centity {model.centity}: the cube above is only its particles.
            </Muted>
          )}
        </div>
      </Stage>
      <InspectorPanel>
        <Section title="Block">
          <SelectField
            label="Look"
            dataPath="model"
            value={model.model ?? ''}
            options={options(
              resourcePackRefs(packs, 'blocks'),
              model.model,
              '(a plain note block)',
            )}
            onChange={(value) => set('model', value || undefined)}
          />
          <NumberField
            label="Hardness"
            dataPath="hardness"
            value={model.hardness}
            placeholder="1.5"
            step={0.5}
            min={-1}
            onChange={(value) => set('hardness', value)}
          />
          <SelectField
            label="Tool"
            dataPath="tool"
            value={model.tool ?? ''}
            options={[
              { value: '', label: '(any)' },
              ...BlockToolValues.map((it) => ({ value: it, label: it })),
            ]}
            onChange={(value) => set('tool', (value || undefined) as BlockTool | undefined)}
          />
          <CheckField
            label="Needs the tool to drop"
            dataPath="requiresTool"
            value={model.requiresTool ?? false}
            onChange={(value) => set('requiresTool', value || undefined)}
          />
          <SelectField
            label="Drops"
            dataPath="drops"
            value={model.drops ?? ''}
            options={options(lootTables, model.drops, '(nothing)')}
            onChange={(value) => set('drops', value || undefined)}
          />
          <NumberField
            label="Ticks between ticks"
            dataPath="tick"
            value={model.tick}
            placeholder="never"
            step={1}
            min={1}
            onChange={(value) => set('tick', whole(value))}
          />
          <SelectField
            label="Centity"
            dataPath="centity"
            value={model.centity ?? ''}
            options={options(centities, model.centity, '(a cube)')}
            onChange={(value) => set('centity', value || undefined)}
          />
          {model.centity && (
            <Hint>
              The block stays a solid cube for collision and mining; the centity is spawned at its
              bottom centre when it's placed, and removed with it.
            </Hint>
          )}
        </Section>
        <Section title="Sounds">
          <SelectField
            label="Placed"
            dataPath="sounds.place"
            value={model.sounds?.place ?? ''}
            options={options(sounds, model.sounds?.place, "(the note block's)")}
            onChange={(value) => edit((draft) => setSound(draft, 'place', value || undefined))}
          />
          <SelectField
            label="Broken"
            dataPath="sounds.break"
            value={model.sounds?.break ?? ''}
            options={options(sounds, model.sounds?.break, "(the note block's)")}
            onChange={(value) => edit((draft) => setSound(draft, 'break', value || undefined))}
          />
        </Section>
        <Section title="Script">
          <ScriptField
            folder={dirname(path)}
            script={model.script}
            dataPath="script"
            suggestedName="script.lua"
            template={newScript('block', '')}
            siblingTemplate={newSiblingFile('block')}
            onChange={(script) =>
              edit((draft) => {
                if (script === undefined) delete draft.script
                else draft.script = script
              })
            }
          />
        </Section>
      </InspectorPanel>
    </EditorScreen>
  )
}
