/**
 * Every field of the selected node, as `docs/format/centity.md` defines it.
 *
 * Adding a field: add its control in the right section below with
 * `dataPath` set to its JSON path inside the node, write it through
 * `edit()` (one call = one undo step; wrap drags in a gesture), and leave a
 * key out rather than writing its default.
 */
import { setKey } from '@/core/draft'
import { memo, useState } from 'react'
import {
  newScript,
  newSiblingFile,
  BillboardValues,
  BODY_DEFAULTS,
  HEALTH_BAR_DEFAULTS,
  ItemTransformValues,
  MoonPhaseValues,
  PHYSICS_DEFAULTS,
  ROPE_LIMITS,
  SpawnWeatherValues,
  SPAWNING_DEFAULTS,
  TEXT_DEFAULTS,
  TextAlignmentValues,
  type BodyDef,
  type Box,
  type CentityFile,
  type HealthBarDef,
  type HealthDef,
  type DisplayDef,
  type HitboxDef,
  type NodeDef,
  type PhysicsDef,
  type ProjectOutline,
  type Vec3,
} from '@/core/format'
import { useClientAssets } from '@/minecraft/client/useClientAssets'
import { usePickerIds } from '@/minecraft/client/usePickerIds'
import { parseBlockState } from '@/minecraft/client/model'
import { dirname, NODE_NAME_PATTERN } from '@/core/paths'
import { useApp, useWorkspace } from '@/state/providers'
import { usePrimary, useView } from '@/editors/views'
import {
  Datalist,
  NumberField,
  Row,
  Section,
  SelectField,
  TextField,
  TriStateField,
  Vec3Field,
  CheckField,
  Check,
  FieldGroup,
  JsonObjectField,
} from '@/ui/fields'
import { Button, IconButton } from '@/ui/Button'
import { Empty, Hint } from '@/ui/text'
import { InspectorPanel } from '@/editors/shared/docks'
import { ScriptField } from '@/editors/shared/ScriptField'
import { glyphAdvancesOf, useGlyphMap } from '@/minecraft/text/glyphs'
import { resourcePackRefs } from '@/minecraft/item/item'
import { blockModelBoxes, canFit, fitKey, fittedHitbox, textDisplayBoxes } from './fit'
import { channelDefault, descendantsOf, renameNode, reparent, setChannel } from './ops'
import {
  formatList,
  setList,
  setNumber,
  setRangeEnd,
  setKeepOnInteract,
  setCondition,
  setSpawning,
  setTimeEnd,
  setTimeMode,
  timeEnd,
  timeMode,
  toggleChoice,
  type SpawnTimeMode,
  type SpawningList,
  type SpawningNumber,
  type SpawningRange,
} from './spawning'

// Memoized: playback changes the view's time every frame, and nothing here depends on it.
export const Inspector = memo(function Inspector({
  path,
  model,
}: {
  path: string
  model: CentityFile
}) {
  const { workspace } = useApp()
  const selected = usePrimary('centity', path)
  const changeView = useView('centity', path)
  const node = selected ? model.nodes?.[selected] : undefined

  if (!selected || !node) {
    return (
      <InspectorPanel>
        <CentitySettings path={path} model={model} />
        <BodySection path={path} model={model} />
        <HealthSection path={path} model={model} />
        <SpawningSection path={path} model={model} />
        <Empty>Select a node to edit it.</Empty>
      </InspectorPanel>
    )
  }

  const ws = workspace.getState()
  const editNode = (recipe: (node: NodeDef) => void) =>
    ws.edit<CentityFile>(path, (draft) => {
      const target = draft.nodes?.[selected]
      if (target) recipe(target)
    })
  const gesture = {
    onGestureStart: () => workspace.getState().beginGesture(path),
    onGestureEnd: () => workspace.getState().endGesture(path),
  }

  return (
    <InspectorPanel>
      <Section title="Node">
        <TextField
          label="Name"
          value={selected}
          onChange={(next) => {
            if (!NODE_NAME_PATTERN.test(next) || model.nodes?.[next]) {
              ws.notify('error', `"${next}" can't be used as a node name here`)
              return
            }
            ws.edit<CentityFile>(path, (draft) => renameNode(draft, selected, next))
            changeView.mapSelection((it) => (it === selected ? next : it))
          }}
        />
        <SelectField
          label="Parent"
          dataPath="parent"
          value={node.parent ?? ''}
          options={[
            { value: '', label: '(none: a root)' },
            ...Object.keys(model.nodes ?? {})
              .filter((name) => name !== selected && !descendantsOf(model, selected).has(name))
              .map((name) => ({ value: name, label: name })),
            // A parent that doesn't exist still shows, so the problem is visible.
            ...(node.parent && !model.nodes?.[node.parent]
              ? [{ value: node.parent, label: `${node.parent} (missing)` }]
              : []),
          ]}
          onChange={(parent) =>
            ws.edit<CentityFile>(path, (draft) => void reparent(draft, selected, parent || null))
          }
        />
      </Section>

      <Section title="Transform">
        {(['translation', 'rotation', 'scale'] as const).map((channel) => (
          <Vec3Field
            key={channel}
            label={channel[0]!.toUpperCase() + channel.slice(1)}
            dataPath={`transform.${channel}`}
            value={node.transform?.[channel]}
            defaultValue={channelDefault(channel)}
            step={channel === 'rotation' ? 5 : channel === 'scale' ? 0.1 : 1 / 16}
            onChange={(value) => editNode((target) => setChannel(target, channel, value))}
            {...gesture}
          />
        ))}
      </Section>

      <DisplaySection node={node} editNode={editNode} />
      <HitboxSection node={node} editNode={editNode} {...gesture} />
      <PhysicsSection node={node} editNode={editNode} />
      <RopeSection name={selected} node={node} editNode={editNode} />
    </InspectorPanel>
  )
})

type EditNode = (recipe: (node: NodeDef) => void) => void

const SPAWNING_LISTS: { key: SpawningList; label: string; placeholder: string }[] = [
  { key: 'worlds', label: 'Worlds', placeholder: 'any (world names)' },
  {
    key: 'biomes',
    label: 'Biomes',
    placeholder: 'any (ruby_grove, minecraft:plains, #minecraft:is_forest)',
  },
  {
    key: 'blocks',
    label: 'Stands on',
    placeholder: 'any (minecraft:grass_block, #minecraft:dirt)',
  },
]

const SPAWNING_RANGES: { key: SpawningRange; label: string; floor?: number }[] = [
  { key: 'light', label: 'Light', floor: SPAWNING_DEFAULTS.minLight },
  { key: 'height', label: 'Height (y)' },
  { key: 'group', label: 'Group size', floor: 1 },
]

const SPAWNING_NUMBERS: { key: SpawningNumber; label: string; fallback: number }[] = [
  { key: 'weight', label: 'Weight', fallback: SPAWNING_DEFAULTS.weight },
  { key: 'cap', label: 'Cap per player', fallback: SPAWNING_DEFAULTS.cap },
  {
    key: 'despawnDistance',
    label: 'Despawn distance',
    fallback: SPAWNING_DEFAULTS.despawnDistance,
  },
]

/**
 * `spawning`: where the centity appears by itself near players. Off when the
 * file has no block; on, every field is optional and an empty one is left
 * out of the file.
 */
function SpawningSection({ path, model }: { path: string; model: CentityFile }) {
  const { workspace } = useApp()
  const edit = (recipe: (draft: CentityFile) => void) =>
    workspace.getState().edit<CentityFile>(path, recipe)
  const spawning = model.spawning
  return (
    <Section
      title="Natural spawning"
      enabled={spawning !== undefined}
      onToggle={(on) => edit((draft) => setSpawning(draft, on))}
    >
      {spawning && (
        <>
          {SPAWNING_LISTS.map(({ key, label, placeholder }) => (
            <TextField
              key={key}
              label={label}
              dataPath={`centity.spawning.${key}`}
              value={formatList(spawning[key])}
              placeholder={placeholder}
              onChange={(text) => edit((draft) => setList(draft, key, text))}
            />
          ))}
          {SPAWNING_RANGES.map(({ key, label, floor }) => (
            <FieldGroup key={key} header={label}>
              {(['min', 'max'] as const).map((end) => (
                <NumberField
                  key={end}
                  label={`${label} ${end}`}
                  dataPath={`centity.spawning.${key}.${end}`}
                  value={spawning[key]?.[end]}
                  placeholder={end}
                  step={1}
                  min={floor}
                  onChange={(value) => edit((draft) => setRangeEnd(draft, key, end, value))}
                />
              ))}
            </FieldGroup>
          ))}
          {SPAWNING_NUMBERS.map(({ key, label, fallback }) => (
            <NumberField
              key={key}
              label={label}
              dataPath={`centity.spawning.${key}`}
              value={spawning[key]}
              placeholder={String(fallback)}
              step={1}
              min={1}
              onChange={(value) => edit((draft) => setNumber(draft, key, value))}
            />
          ))}
          <SelectField<SpawnTimeMode>
            label="Time of day"
            dataPath="centity.spawning.time"
            value={timeMode(spawning.time)}
            options={[
              { value: 'any', label: 'any time' },
              { value: 'day', label: 'day (23000–12999)' },
              { value: 'night', label: 'night (13000–22999)' },
              { value: 'range', label: 'ticks of the day' },
            ]}
            onChange={(mode) => edit((draft) => setTimeMode(draft, mode))}
          />
          {timeMode(spawning.time) === 'range' && (
            <FieldGroup header="Ticks of the day">
              {(['min', 'max'] as const).map((end) => (
                <NumberField
                  key={end}
                  label={`Time ${end}`}
                  dataPath={`centity.spawning.time.${end}`}
                  value={timeEnd(spawning.time, end)}
                  placeholder={end === 'min' ? '0' : '23999'}
                  step={1}
                  min={0}
                  onChange={(value) => edit((draft) => setTimeEnd(draft, end, value))}
                />
              ))}
            </FieldGroup>
          )}
          <FieldGroup header="Moon phase" data-path="centity.spawning.moonPhase">
            {MoonPhaseValues.map((phase) => (
              <Check
                key={phase}
                label={phase.replace(/_/g, ' ')}
                checked={spawning.moonPhase?.includes(phase) ?? false}
                onChange={(on) => edit((draft) => toggleChoice(draft, 'moonPhase', phase, on))}
              />
            ))}
          </FieldGroup>
          <FieldGroup header="Weather" data-path="centity.spawning.weather">
            {SpawnWeatherValues.map((weather) => (
              <Check
                key={weather}
                label={weather}
                checked={spawning.weather?.includes(weather) ?? false}
                onChange={(on) => edit((draft) => toggleChoice(draft, 'weather', weather, on))}
              />
            ))}
          </FieldGroup>
          <TextField
            label="Only when"
            dataPath="centity.spawning.when"
            value={spawning.when ?? ''}
            placeholder="none (a condition from nf.spawning.condition)"
            onChange={(text) => edit((draft) => setCondition(draft, text))}
          />
          <CheckField
            label="Interaction keeps one"
            dataPath="centity.spawning.keepOnInteract"
            value={spawning.keepOnInteract === true}
            onChange={(on) => edit((draft) => setKeepOnInteract(draft, on))}
          />
          <Hint>
            Placed {SPAWNING_DEFAULTS.minDistance} to {SPAWNING_DEFAULTS.maxDistance} blocks from a
            player. A natural centity isn't saved, and goes when no player is within its despawn
            distance, until a script calls keep() (or, with the option above, a player clicks it).
          </Hint>
        </>
      )}
    </Section>
  )
}

function CentitySettings({ path, model }: { path: string; model: CentityFile }) {
  const { workspace } = useApp()
  const edit = (recipe: (draft: CentityFile) => void) =>
    workspace.getState().edit<CentityFile>(path, recipe)
  return (
    <Section title="Centity">
      <TextField
        label="Display name"
        dataPath="centity.name"
        value={model.name}
        placeholder="(none)"
        onChange={(name) => edit((draft) => setKey(draft, 'name', name || undefined))}
      />
      <ScriptField
        folder={dirname(path)}
        script={model.script}
        dataPath="centity.script"
        suggestedName="script.lua"
        template={newScript('centity', '')}
        siblingTemplate={newSiblingFile('centity')}
        onChange={(script) => edit((draft) => setKey(draft, 'script', script))}
      />
      <JsonObjectField
        label="Properties"
        dataPath="centity.properties"
        value={model.properties}
        onChange={(value) => edit((draft) => setKey(draft, 'properties', value))}
      />
      <Hint>
        Properties are the project&apos;s own facts about it, any JSON: scripts read them with{' '}
        <code>centity:properties()</code>.
      </Hint>
    </Section>
  )
}

const BODY_NUMBERS: { key: keyof BodyDef; label: string; step: number; min?: number }[] = [
  { key: 'width', label: 'Width', step: 0.1, min: 0 },
  { key: 'height', label: 'Height', step: 0.1, min: 0 },
  { key: 'gravity', label: 'Gravity', step: 1 },
  { key: 'maxFallSpeed', label: 'Max fall speed', step: 1, min: 0 },
  { key: 'drag', label: 'Drag in air', step: 0.01, min: 0 },
  { key: 'liquidDrag', label: 'Drag in liquid', step: 0.01, min: 0 },
  { key: 'liquidGravity', label: 'Gravity in liquid', step: 0.05, min: 0 },
  { key: 'acceleration', label: 'Acceleration', step: 1, min: 0 },
  { key: 'airAcceleration', label: 'Air acceleration', step: 1, min: 0 },
  { key: 'friction', label: 'Friction', step: 1, min: 0 },
  { key: 'stepHeight', label: 'Step height', step: 0.1, min: 0 },
  { key: 'bounce', label: 'Bounce', step: 0.05, min: 0 },
]

/** `body`: the box the server walks, drops and stops against blocks, moving the whole centity. */
function BodySection({ path, model }: { path: string; model: CentityFile }) {
  const { workspace } = useApp()
  const body = model.body
  const editBody = (recipe: (body: BodyDef) => void) =>
    workspace.getState().edit<CentityFile>(path, (draft) => {
      if (draft.body) recipe(draft.body)
    })
  return (
    <Section
      title="Body"
      enabled={body !== undefined}
      onToggle={(on) =>
        workspace
          .getState()
          .edit<CentityFile>(path, (draft) => setKey(draft, 'body', on ? {} : undefined))
      }
    >
      {body && (
        <>
          {BODY_NUMBERS.map(({ key, label, step, min }) => (
            <NumberField
              key={key}
              label={label}
              dataPath={`centity.body.${key}`}
              value={body[key] as number | undefined}
              placeholder={String(BODY_DEFAULTS[key as keyof typeof BODY_DEFAULTS])}
              step={step}
              min={min}
              onChange={(value) => editBody((b) => setKey(b, key, value as never))}
            />
          ))}
          <CheckField
            label="Flies"
            dataPath="centity.body.fly"
            value={body.fly === true}
            onChange={(on) => editBody((b) => setKey(b, 'fly', on || undefined))}
          />
          <CheckField
            label="Collides with blocks"
            dataPath="centity.body.blocks"
            value={body.blocks !== false}
            onChange={(on) => editBody((b) => setKey(b, 'blocks', on ? undefined : false))}
          />
          <Hint>
            An upright box {BODY_DEFAULTS.maxWidth} blocks across and {BODY_DEFAULTS.maxHeight} tall
            at most, standing at the centity&apos;s position: scripts walk, jump and push it. Rates
            are per second.
          </Hint>
        </>
      )}
    </Section>
  )
}

const BAR_TEXTS: { key: 'glyph' | 'filled' | 'empty'; label: string }[] = [
  { key: 'glyph', label: 'Piece' },
  { key: 'filled', label: 'Before full pieces' },
  { key: 'empty', label: 'Before empty pieces' },
]

/** `health`: what `damage` takes and `death` hears at 0, and the bar over it. */
function HealthSection({ path, model }: { path: string; model: CentityFile }) {
  const { workspace } = useApp()
  const health = model.health
  const editHealth = (recipe: (health: HealthDef) => void) =>
    workspace.getState().edit<CentityFile>(path, (draft) => {
      if (draft.health) recipe(draft.health)
    })
  const editBar = (recipe: (bar: HealthBarDef) => void) =>
    editHealth((h) => {
      if (h.bar) recipe(h.bar)
    })
  const bar = health?.bar
  return (
    <Section
      title="Health"
      enabled={health !== undefined}
      onToggle={(on) =>
        workspace
          .getState()
          .edit<CentityFile>(path, (draft) => setKey(draft, 'health', on ? { max: 20 } : undefined))
      }
    >
      {health && (
        <>
          <NumberField
            label="Max"
            dataPath="centity.health.max"
            value={health.max}
            step={1}
            min={0}
            onChange={(value) => value !== undefined && editHealth((h) => (h.max = value))}
          />
          <CheckField
            label="Removed when it dies"
            dataPath="centity.health.removeOnDeath"
            value={health.removeOnDeath !== false}
            onChange={(on) => editHealth((h) => setKey(h, 'removeOnDeath', on ? undefined : false))}
          />
          <CheckField
            label="Health bar"
            dataPath="centity.health.bar"
            value={bar !== undefined}
            onChange={(on) => editHealth((h) => setKey(h, 'bar', on ? {} : undefined))}
          />
          {bar && (
            <FieldGroup header="Bar">
              <NumberField
                label="Segments"
                dataPath="centity.health.bar.segments"
                value={bar.segments}
                placeholder={String(HEALTH_BAR_DEFAULTS.segments)}
                step={1}
                min={1}
                onChange={(value) =>
                  editBar((b) =>
                    setKey(
                      b,
                      'segments',
                      value === undefined
                        ? undefined
                        : Math.min(HEALTH_BAR_DEFAULTS.maxSegments, Math.round(value)),
                    ),
                  )
                }
              />
              <NumberField
                label="Shows for (ticks)"
                dataPath="centity.health.bar.showTicks"
                value={bar.showTicks}
                placeholder={String(HEALTH_BAR_DEFAULTS.showTicks)}
                step={10}
                min={0}
                onChange={(value) =>
                  editBar((b) =>
                    setKey(b, 'showTicks', value === undefined ? undefined : Math.round(value)),
                  )
                }
              />
              <NumberField
                label="Offset"
                dataPath="centity.health.bar.offset"
                value={bar.offset}
                placeholder={String(HEALTH_BAR_DEFAULTS.offset)}
                step={0.05}
                onChange={(value) => editBar((b) => setKey(b, 'offset', value))}
              />
              {BAR_TEXTS.map(({ key, label }) => (
                <TextField
                  key={key}
                  label={label}
                  dataPath={`centity.health.bar.${key}`}
                  value={bar[key]}
                  placeholder={HEALTH_BAR_DEFAULTS[key]}
                  onChange={(value) => editBar((b) => setKey(b, key, value || undefined))}
                />
              ))}
              <Hint>
                0 ticks always shows it. A text display node named{' '}
                <code>{HEALTH_BAR_DEFAULTS.node}</code> draws it where you place it; without one the
                server adds it over the hitboxes.
              </Hint>
            </FieldGroup>
          )}
        </>
      )}
    </Section>
  )
}

/** A node's `rope`: its display drawn by a line of links that scripts string between two ends. */
function RopeSection({
  name,
  node,
  editNode,
}: {
  name: string
  node: NodeDef
  editNode: EditNode
}) {
  const rope = node.rope
  return (
    <Section
      title="Rope"
      enabled={rope !== undefined}
      onToggle={(on) => editNode((target) => setKey(target, 'rope', on ? { links: 8 } : undefined))}
    >
      {rope && (
        <>
          <NumberField
            label="Links"
            dataPath="rope.links"
            value={rope.links}
            step={1}
            min={1}
            onChange={(value) =>
              value !== undefined &&
              editNode((target) => {
                if (target.rope)
                  target.rope.links = Math.min(ROPE_LIMITS.maxLinks, Math.round(value))
              })
            }
          />
          <NumberField
            label="Link length"
            dataPath="rope.linkLength"
            value={rope.linkLength}
            placeholder={String(ROPE_LIMITS.linkLength)}
            step={0.05}
            min={0}
            onChange={(value) =>
              editNode((target) => {
                if (target.rope)
                  setKey(
                    target.rope,
                    'linkLength',
                    value === undefined ? undefined : Math.min(ROPE_LIMITS.maxLinkLength, value),
                  )
              })
            }
          />
          <Hint>
            Its display is drawn by links {name}[1] to {name}[{rope.links}], each stretched along Z
            to its length; <code>node:rope_between</code> strings them. A rope has no hitbox or
            physics.
          </Hint>
        </>
      )}
    </Section>
  )
}

const NO_RESOURCE_PACKS: NonNullable<ProjectOutline>['resourcePacks'] = {}

function DisplaySection({ node, editNode }: { node: NodeDef; editNode: EditNode }) {
  const gameData = useWorkspace((s) => s.gameData)
  const display = node.display
  const type = display?.type ?? 'none'
  const { blocks, items } = usePickerIds()
  const packs = useWorkspace((s) => s.outline?.resourcePacks ?? NO_RESOURCE_PACKS)
  const looks = resourcePackRefs(packs, 'items')

  const switchType = (next: string) =>
    editNode((target) => {
      if (next === 'none') delete target.display
      else if (next === 'block') target.display = { type: 'block', block: 'minecraft:stone' }
      else if (next === 'item') target.display = { type: 'item', item: 'minecraft:stick' }
      else if (next === 'model') target.display = { type: 'model', model: looks[0] ?? '' }
      else target.display = { type: 'text', text: 'Text' }
    })

  const editDisplay = <T extends DisplayDef>(recipe: (d: T) => void) =>
    editNode((target) => {
      if (target.display) recipe(target.display as T)
    })

  return (
    <Section title="Display">
      <SelectField
        label="Type"
        dataPath="display"
        value={type}
        options={['none', 'block', 'item', 'model', 'text'] as const}
        onChange={switchType}
      />
      {display?.type === 'block' && (
        <>
          <TextField
            label="Block"
            dataPath="display.block"
            value={display.block}
            list={blocks.length ? 'nf-blocks' : undefined}
            placeholder="minecraft:stone"
            onChange={(block) => editDisplay<typeof display>((d) => (d.block = block))}
          />
          {blocks.length > 0 && <Datalist id="nf-blocks" values={blocks} />}
          <BlockProperties
            state={display.block}
            properties={gameData?.blocks?.[parseBlockState(display.block).id]?.properties}
            defaults={gameData?.blocks?.[parseBlockState(display.block).id]?.defaults}
            onChange={(block) => editDisplay<typeof display>((d) => (d.block = block))}
          />
          {!gameData && (
            <Hint>
              Block names aren't checked until a dev server has exported this version's game data.
            </Hint>
          )}
        </>
      )}
      {display?.type === 'item' && (
        <>
          <TextField
            label="Item"
            dataPath="display.item"
            value={display.item}
            list={items.length ? 'nf-items' : undefined}
            onChange={(item) => editDisplay<typeof display>((d) => (d.item = item))}
          />
          {items.length > 0 && <Datalist id="nf-items" values={items} />}
          <SelectField
            label="Item transform"
            dataPath="display.itemTransform"
            value={display.itemTransform ?? ''}
            options={[
              { value: '', label: 'Default (none)' },
              ...ItemTransformValues.map((it) => ({ value: it, label: it })),
            ]}
            onChange={(value) =>
              editDisplay<typeof display>((d) => setKey(d, 'itemTransform', value || undefined))
            }
          />
        </>
      )}
      {display?.type === 'model' && (
        <>
          <SelectField
            label="Look"
            dataPath="display.model"
            value={display.model}
            options={[
              ...looks,
              ...(looks.includes(display.model)
                ? []
                : [{ value: display.model, label: `${display.model || '(none)'} (missing)` }]),
            ]}
            onChange={(model) => editDisplay<typeof display>((d) => (d.model = model))}
          />
          <SelectField
            label="Item transform"
            dataPath="display.itemTransform"
            value={display.itemTransform ?? ''}
            options={[
              { value: '', label: 'Default (none: as modelled)' },
              ...ItemTransformValues.map((it) => ({ value: it, label: it })),
            ]}
            onChange={(value) =>
              editDisplay<typeof display>((d) => setKey(d, 'itemTransform', value || undefined))
            }
          />
          <Hint>
            An entry of a resource pack&apos;s items: a model file, a block look or a picture. A
            script swaps it with node:set_model.
          </Hint>
        </>
      )}
      {display?.type === 'text' && (
        <>
          <TextField
            label="Text"
            multiline
            dataPath="display.text"
            value={display.text}
            onChange={(text) => editDisplay<typeof display>((d) => (d.text = text))}
          />
          <SelectField
            label="Billboard"
            dataPath="display.billboard"
            value={display.billboard ?? ''}
            options={[
              { value: '', label: 'Default (center)' },
              ...BillboardValues.map((it) => ({ value: it, label: it })),
            ]}
            onChange={(value) =>
              editDisplay<typeof display>((d) => setKey(d, 'billboard', value || undefined))
            }
          />
          <SelectField
            label="Alignment"
            dataPath="display.alignment"
            value={display.alignment ?? ''}
            options={[
              { value: '', label: 'Default (center)' },
              ...TextAlignmentValues.map((it) => ({ value: it, label: it })),
            ]}
            onChange={(value) =>
              editDisplay<typeof display>((d) => setKey(d, 'alignment', value || undefined))
            }
          />
          <TextField
            label="Background"
            dataPath="display.background"
            value={display.background}
            placeholder="#40000000"
            onChange={(value) =>
              editDisplay<typeof display>((d) => setKey(d, 'background', value || undefined))
            }
          />
          <NumberField
            label="Line width"
            dataPath="display.lineWidth"
            value={display.lineWidth}
            placeholder={String(TEXT_DEFAULTS.lineWidth)}
            step={10}
            onChange={(value) => editDisplay<typeof display>((d) => setKey(d, 'lineWidth', value))}
          />
          <TriStateField
            label="See through"
            dataPath="display.seeThrough"
            value={display.seeThrough}
            defaultLabel="off"
            onChange={(value) => editDisplay<typeof display>((d) => setKey(d, 'seeThrough', value))}
          />
          <TriStateField
            label="Shadow"
            dataPath="display.shadow"
            value={display.shadow}
            defaultLabel="off"
            onChange={(value) => editDisplay<typeof display>((d) => setKey(d, 'shadow', value))}
          />
        </>
      )}
    </Section>
  )
}

/** A select per block property, when game data says which properties the block has. */
function BlockProperties({
  state,
  properties,
  defaults,
  onChange,
}: {
  state: string
  properties: Record<string, string[]> | undefined
  defaults: Record<string, string> | undefined
  onChange: (state: string) => void
}) {
  if (!properties || Object.keys(properties).length === 0) return null
  const parsed = parseBlockState(state)
  const write = (name: string, value: string) => {
    const next = { ...parsed.properties }
    if (value === '') delete next[name]
    else next[name] = value
    const entries = Object.entries(next)
    onChange(
      entries.length
        ? `${parsed.id}[${entries.map(([k, v]) => `${k}=${v}`).join(',')}]`
        : parsed.id,
    )
  }
  return (
    <>
      {Object.entries(properties).map(([name, values]) => (
        <SelectField
          key={name}
          label={name}
          dataPath="display.block"
          value={parsed.properties[name] ?? ''}
          options={[
            { value: '', label: `Default (${defaults?.[name] ?? values[0] ?? ''})` },
            ...values.map((value) => ({ value, label: value })),
          ]}
          onChange={(value) => write(name, value)}
        />
      ))}
    </>
  )
}

type HitboxMode = 'cube' | 'boxes' | 'collision'

function HitboxSection({
  node,
  editNode,
  onGestureStart,
  onGestureEnd,
}: {
  node: NodeDef
  editNode: EditNode
  onGestureStart: () => void
  onGestureEnd: () => void
}) {
  const { workspace } = useApp()
  const gameData = useWorkspace((s) => s.gameData)
  const assets = useClientAssets()
  const [fitting, setFitting] = useState(false)
  const hitbox = node.hitbox
  const mode: HitboxMode =
    hitbox?.shape === 'collision' ? 'collision' : hitbox?.boxes ? 'boxes' : 'cube'
  const editHitbox = (recipe: (h: HitboxDef) => void) =>
    editNode((target) => {
      if (target.hitbox) recipe(target.hitbox)
    })

  const advances = useWorkspace((s) => s.glyphAdvances)
  const glyphs = useGlyphMap()
  const fit = async () => {
    const display = node.display
    if (!display || !canFit(display)) return
    if (display.type === 'text') {
      const boxes = textDisplayBoxes(display, advances, glyphAdvancesOf(glyphs))
      if (boxes) editNode((target) => (target.hitbox = fittedHitbox(target.hitbox, display, boxes)))
      if (!advances) {
        workspace
          .getState()
          .notify(
            'info',
            "Fitted with estimated glyph widths: import this version's client assets in Settings → Minecraft for exact ones.",
          )
      }
      return
    }
    if (display.type !== 'block') return
    setFitting(true)
    try {
      const boxes = await blockModelBoxes(display.block, {
        gameData,
        assets,
      })
      if (!boxes) {
        workspace
          .getState()
          .notify(
            'error',
            `No model for ${display.block}: import this version's client assets in Settings → Minecraft.`,
          )
        return
      }
      editNode((target) => {
        target.hitbox = fittedHitbox(target.hitbox, display, boxes)
      })
    } finally {
      setFitting(false)
    }
  }

  const stale =
    hitbox?.fittedTo !== undefined &&
    fitKey(node.display) !== null &&
    hitbox.fittedTo !== fitKey(node.display)

  return (
    <Section
      title="Hitbox"
      enabled={hitbox !== undefined}
      onToggle={(on) => editNode((target) => (on ? (target.hitbox = {}) : delete target.hitbox))}
    >
      {hitbox && (
        <>
          <SelectField
            label="Shape"
            dataPath="hitbox.shape"
            value={mode}
            options={[
              { value: 'cube', label: 'Unit cube' },
              { value: 'boxes', label: 'Boxes' },
              { value: 'collision', label: "Block's collision shape (live)" },
            ]}
            onChange={(next) =>
              editHitbox((h) => {
                delete h.fittedTo
                if (next === 'cube') {
                  delete h.boxes
                  delete h.shape
                } else if (next === 'boxes') {
                  delete h.shape
                  h.boxes = h.boxes ?? [{ min: [0, 0, 0], max: [1, 1, 1] }]
                } else {
                  delete h.boxes
                  h.shape = 'collision'
                }
              })
            }
          />
          {mode === 'boxes' &&
            (hitbox.boxes ?? []).map((box, index) => (
              <BoxFields
                key={index}
                label={`Box ${index + 1}`}
                box={box}
                dataPath={`hitbox.boxes[${index}]`}
                onChange={(next) => editHitbox((h) => (h.boxes![index] = next))}
                onRemove={() => editHitbox((h) => void h.boxes!.splice(index, 1))}
                onGestureStart={onGestureStart}
                onGestureEnd={onGestureEnd}
              />
            ))}
          {mode === 'boxes' && (
            <Button
              size="small"
              icon="plus"
              onClick={() =>
                editHitbox(
                  (h) => (h.boxes = [...(h.boxes ?? []), { min: [0, 0, 0], max: [1, 1, 1] }]),
                )
              }
            >
              Add box
            </Button>
          )}
          <TriStateField
            label="Raycast"
            dataPath="hitbox.raycast"
            value={hitbox.raycast}
            defaultLabel="on for several boxes"
            onChange={(value) => editHitbox((h) => setKey(h, 'raycast', value))}
          />
          <Row label="Fit">
            <Button
              size="small"
              disabled={!canFit(node.display) || fitting}
              title={
                node.display?.type === 'text'
                  ? canFit(node.display)
                    ? 'Write the text quad as a box'
                    : 'A text display that turns to face viewers has no one shape; use the fixed billboard'
                  : canFit(node.display)
                    ? 'Write the block model as boxes'
                    : 'Needs a block display, or a fixed text display'
              }
              onClick={() => void fit()}
            >
              Fit to display
            </Button>
          </Row>
          {hitbox.fittedTo && (
            <Hint tone={stale ? 'warning' : undefined} data-path="hitbox.fittedTo">
              Fitted to <code>{hitbox.fittedTo}</code>
              {stale && ' — the display changed; fit again.'}
            </Hint>
          )}
        </>
      )}
      {!hitbox && <Hint>No hitbox: this node can't be clicked.</Hint>}
    </Section>
  )
}

function BoxFields({
  label,
  box,
  onChange,
  onRemove,
  dataPath,
  onGestureStart,
  onGestureEnd,
}: {
  label: string
  box: Box
  onChange: (box: Box) => void
  onRemove?: () => void
  dataPath: string
  onGestureStart?: () => void
  onGestureEnd?: () => void
}) {
  return (
    <FieldGroup
      data-path={dataPath}
      header={
        <>
          <span>{label}</span>
          {onRemove && <IconButton icon="trash" label={`Remove ${label}`} onClick={onRemove} />}
        </>
      }
    >
      <Vec3Field
        label={`${label} min`}
        value={box.min}
        defaultValue={[0, 0, 0]}
        step={1 / 16}
        onChange={(min: Vec3) => onChange({ ...box, min })}
        onGestureStart={onGestureStart}
        onGestureEnd={onGestureEnd}
      />
      <Vec3Field
        label={`${label} max`}
        value={box.max}
        defaultValue={[1, 1, 1]}
        step={1 / 16}
        onChange={(max: Vec3) => onChange({ ...box, max })}
        onGestureStart={onGestureStart}
        onGestureEnd={onGestureEnd}
      />
    </FieldGroup>
  )
}

const PHYSICS_NUMBERS: {
  key: keyof PhysicsDef
  label: string
  placeholder: string
  step: number
}[] = [
  { key: 'gravity', label: 'Gravity', placeholder: String(PHYSICS_DEFAULTS.gravity), step: 1 },
  { key: 'mass', label: 'Mass', placeholder: 'volume', step: 0.1 },
  {
    key: 'bounciness',
    label: 'Bounciness',
    placeholder: String(PHYSICS_DEFAULTS.bounciness),
    step: 0.05,
  },
  {
    key: 'friction',
    label: 'Friction',
    placeholder: String(PHYSICS_DEFAULTS.friction),
    step: 0.05,
  },
  { key: 'drag', label: 'Drag', placeholder: String(PHYSICS_DEFAULTS.drag), step: 0.01 },
  {
    key: 'angularDrag',
    label: 'Angular drag',
    placeholder: String(PHYSICS_DEFAULTS.angularDrag),
    step: 0.01,
  },
  { key: 'maxSpeed', label: 'Max speed', placeholder: String(PHYSICS_DEFAULTS.maxSpeed), step: 1 },
  { key: 'maxSpin', label: 'Max spin', placeholder: String(PHYSICS_DEFAULTS.maxSpin), step: 10 },
]

const PHYSICS_FLAGS: { key: 'rotates' | 'sleeps' | 'blocks' | 'entities'; label: string }[] = [
  { key: 'rotates', label: 'Rotates' },
  { key: 'sleeps', label: 'Sleeps at rest' },
  { key: 'blocks', label: 'Collides with blocks' },
  { key: 'entities', label: 'Collides with centities' },
]

function PhysicsSection({ node, editNode }: { node: NodeDef; editNode: EditNode }) {
  const physics = node.physics
  const editPhysics = (recipe: (p: PhysicsDef) => void) =>
    editNode((target) => {
      if (target.physics) recipe(target.physics)
    })
  return (
    <Section
      title="Physics"
      enabled={physics !== undefined}
      onToggle={(on) => editNode((target) => (on ? (target.physics = {}) : delete target.physics))}
    >
      {physics && (
        <>
          {PHYSICS_NUMBERS.map(({ key, label, placeholder, step }) => (
            <NumberField
              key={key}
              label={label}
              dataPath={`physics.${key}`}
              value={physics[key] as number | undefined}
              placeholder={placeholder}
              step={step}
              onChange={(value) => editPhysics((p) => setKey(p, key, value as never))}
            />
          ))}
          <SelectField
            label="Shape"
            dataPath="physics.shape"
            value={physics.shape ?? ''}
            options={[
              { value: '', label: 'Follow the hitbox' },
              { value: 'box', label: 'One box' },
              { value: 'sphere', label: 'Sphere (rolls)' },
            ]}
            onChange={(value) => editPhysics((p) => setKey(p, 'shape', value || undefined))}
          />
          <CheckField
            label="Own collider"
            dataPath="physics.collider"
            value={physics.collider !== undefined}
            onChange={(on) =>
              editPhysics((p) =>
                setKey(p, 'collider', on ? { min: [0, 0, 0], max: [1, 1, 1] } : undefined),
              )
            }
          />
          {physics.collider && (
            <BoxFields
              label="Collider"
              dataPath="physics.collider"
              box={physics.collider}
              onChange={(collider) => editPhysics((p) => (p.collider = collider))}
            />
          )}
          {PHYSICS_FLAGS.map(({ key, label }) => (
            <TriStateField
              key={key}
              label={label}
              dataPath={`physics.${key}`}
              value={physics[key]}
              defaultLabel="on"
              onChange={(value) => editPhysics((p) => setKey(p, key, value))}
            />
          ))}
        </>
      )}
    </Section>
  )
}
