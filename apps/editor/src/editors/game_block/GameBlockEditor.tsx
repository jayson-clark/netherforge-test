/**
 * A game block file (`game_blocks/<id>.json`, docs/format/game-block.md):
 * which of the game's blocks it gives rules to (ids and `#tags`, suggested
 * from the target version's game data once it's exported), how long they take
 * to mine and with what, and what they drop. Every field left empty is the
 * game's own, which the stage says in words.
 */
import { useMemo } from 'react'
import { setKey } from '@/core/draft'
import { BLOCK_REGISTRY, BlockToolValues, type BlockTool, type GameBlockFile } from '@/core/format'
import { InspectorPanel } from '@/editors/shared/docks'
import { EditorBar, EditorScreen, Stage } from '@/editors/shared/EditorLayout'
import { fieldPath } from '@/editors/shared/focus'
import { EditAsJson, useModelDoc } from '@/editors/shared/modelDoc'
import { RawDocView } from '@/editors/shared/RawDocView'
import { useFocusRequests } from '@/editors/shared/useFocusRequests'
import { useWorkspace } from '@/state/providers'
import { IconButton } from '@/ui/Button'
import { Datalist, NumberField, Section, SelectField, TextField, TriStateField } from '@/ui/fields'
import { Hint, Muted } from '@/ui/text'
import { addBlock, blockChoices, minedByServer, removeBlock, setBlock } from './ops'
import styles from './GameBlockEditor.module.css'

const BLOCKS = 'nf-game-blocks'
const NONE: string[] = []
const NO_TAGS: Record<string, string[]> = {}

/** The choices of the drops field: the game's, each loot table, and the current one when it's missing. */
function dropOptions(tables: string[], current: string | undefined) {
  return [
    { value: '', label: "(the game's drops)" },
    ...tables.map((it) => ({ value: it, label: it })),
    ...(current && !tables.includes(current)
      ? [{ value: current, label: `${current} (missing)` }]
      : []),
  ]
}

export function GameBlockEditor({ path }: { path: string }) {
  const { doc, model, edit } = useModelDoc<GameBlockFile>(path)
  const lootTables = useWorkspace((s) => s.outline?.resources.loot_table ?? NONE)
  const gameBlocks = useWorkspace((s) => s.gameData?.registries?.[BLOCK_REGISTRY] ?? NONE)
  const gameTags = useWorkspace((s) => s.gameData?.tags?.[BLOCK_REGISTRY] ?? NO_TAGS)
  const choices = useMemo(
    () => blockChoices(gameBlocks, Object.keys(gameTags)),
    [gameBlocks, gameTags],
  )
  const id =
    path
      .split('/')
      .pop()
      ?.replace(/\.json$/, '') ?? ''
  // Every path in the file is an inspector field's.
  useFocusRequests(path, (segments) => segments)

  if (!doc) return null
  if (!model) return <RawDocView path={path} />

  const set = <K extends keyof GameBlockFile>(key: K, value: GameBlockFile[K] | undefined) =>
    edit((draft) => setKey(draft, key, value))
  const blocks = model.blocks ?? []
  return (
    <EditorScreen>
      <EditorBar title={id}>
        <EditAsJson path={path} />
      </EditorBar>
      <Stage>
        <div className={styles.summary} aria-label="What these rules do">
          {blocks.length === 0 ? (
            <Muted>No blocks yet: add the game&apos;s blocks these rules are for.</Muted>
          ) : (
            <Muted>
              {blocks.join(', ')}:{' '}
              {minedByServer(model)
                ? `mined by the server, ${model.hardness ?? "the game's"} hardness, with ${model.tool ?? "the game's tools"}`
                : 'mined as the game mines them'}
              , dropping {model.drops ? `the loot table ${model.drops}` : "the game's drops"}.
            </Muted>
          )}
          <Hint>
            Everything else stays the game&apos;s: its shape, light, sounds and physics. Redraw its
            textures in a resource pack to make it look like the project&apos;s.
          </Hint>
        </div>
      </Stage>
      <InspectorPanel>
        <Datalist id={BLOCKS} values={choices} />
        <Section title="Blocks">
          <div className={styles.list} data-path={fieldPath(['blocks'])}>
            <div className={styles.listHeader}>
              <span>The game&apos;s blocks and #tags</span>
              <IconButton
                icon="plus"
                size={14}
                label="Add a block"
                onClick={() => edit((d) => addBlock(d, ''))}
              />
            </div>
            {blocks.map((block, index) => (
              <div key={index} className={styles.row}>
                <TextField
                  label={`Block ${index + 1}`}
                  dataPath={fieldPath(['blocks', index])}
                  value={block}
                  list={BLOCKS}
                  placeholder="minecraft:deepslate_bricks"
                  onChange={(value) => edit((d) => setBlock(d, index, value))}
                />
                <IconButton
                  icon="trash"
                  size={12}
                  label={`Remove ${block || 'this block'}`}
                  onClick={() => edit((d) => removeBlock(d, index))}
                />
              </div>
            ))}
          </div>
          <Hint>Stairs, slabs and walls are blocks of their own: list each one you mean.</Hint>
        </Section>
        <Section title="Mining">
          <NumberField
            label="Hardness"
            dataPath="hardness"
            value={model.hardness}
            placeholder="the game's"
            step={0.5}
            min={-1}
            onChange={(value) => set('hardness', value)}
          />
          <SelectField
            label="Tool"
            dataPath="tool"
            value={model.tool ?? ''}
            options={[
              { value: '', label: "(the game's)" },
              ...BlockToolValues.map((it) => ({ value: it, label: it })),
            ]}
            onChange={(value) => set('tool', (value || undefined) as BlockTool | undefined)}
          />
          <TriStateField
            label="Needs the tool to drop"
            dataPath="requiresTool"
            value={model.requiresTool}
            defaultLabel="the game's rule"
            onChange={(value) => set('requiresTool', value)}
          />
          <SelectField
            label="Drops"
            dataPath="drops"
            value={model.drops ?? ''}
            options={dropOptions(lootTables, model.drops)}
            onChange={(value) => set('drops', value || undefined)}
          />
          {minedByServer(model) && (
            <Hint>
              The server mines these blocks: players&apos; own games are slowed while they look at
              one, and the server shows the cracks and breaks it.
            </Hint>
          )}
        </Section>
      </InspectorPanel>
    </EditorScreen>
  )
}
