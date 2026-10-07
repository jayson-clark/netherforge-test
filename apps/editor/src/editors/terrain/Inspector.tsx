/**
 * The terrain's inspector: every part of the file as fields, in the order a column of the world is
 * made (terrain and its 3D ground, layers, caves, ores, decorations), then the biomes (with their own terrain and the jitter of
 * their borders), structures, and the script the file hands stages to. Each is an edit through `edit`; the
 * preview beside it redraws from the file as it then is.
 */
import { useApp } from '@/state/providers'
import { setKey } from '@/core/draft'
import {
  BIOME_REGISTRY,
  CaveTypeValues,
  DecorationPlacementValues,
  NoiseFractalValues,
  NoiseTypeValues,
  OreDistributionValues,
  TERRAIN_DEFAULTS,
  TERRAIN_LIMITS,
  newTerrainScript,
  type CaveType,
  type ClimateRange,
  type DecorationPlacement,
  type Islands,
  type NoiseDef,
  type NoiseFractal,
  type NoiseType,
  type OreDistribution,
  type TerrainFile,
} from '@/core/format'
import { BIOME_FOLDER, biomeChoices } from '@/editors/biome/ops'
import { fieldPath } from '@/editors/shared/focus'
import { ID_NAMES, nameProblem } from '@/editors/shared/KeyedList'
import { usePickerIds } from '@/minecraft/client/usePickerIds'
import { useWorkspace } from '@/state/providers'
import { Button, IconButton } from '@/ui/Button'
import { ask } from '@/ui/dialogs'
import {
  CheckField,
  Datalist,
  FieldGroup,
  NumberField,
  Section,
  SelectField,
  TextField,
} from '@/ui/fields'
import { Hint, Muted } from '@/ui/text'
import {
  addEntry,
  addLayer,
  blockKind,
  deleteEntry,
  firstOre,
  layersAt,
  moveLayer,
  namesOf,
  newEntry,
  noiseEntry,
  octavesOf,
  removeLayer,
  renameEntry,
  setAreaDensity,
  setAreaOwnLayers,
  setBlock,
  setDensity,
  setIslands,
  setInArea,
  setLayer,
  setNoiseField,
  setScriptBlocks,
  setScripted,
  setStone,
  type BlockChoice,
  type AreaNoises,
  type BlockKind,
  type Collection,
  type LayerList,
} from './ops'

type Edit = (recipe: (draft: TerrainFile) => void) => void

const BLOCKS_LIST = 'nf-terrain-blocks'
const BIOMES_LIST = 'nf-terrain-biomes'

const whole = (value: number | undefined) => (value === undefined ? undefined : Math.round(value))

/** Adds a named entry after asking its name; the name is checked against the ones taken. */
async function askName(one: string, taken: string[], initial = ''): Promise<string | null> {
  return ask.prompt({
    title: `New ${one}`,
    label: 'Name',
    initial,
    confirmLabel: 'Add',
    validate: (value) => nameProblem(value, taken.includes(value), one, ID_NAMES),
  })
}

export function TerrainInspector({
  path,
  model,
  edit,
}: {
  path: string
  model: TerrainFile
  edit: Edit
}) {
  const { workspace } = useApp()
  const { blocks } = usePickerIds()
  const gameData = useWorkspace((s) => s.gameData)
  const projectBiomes = useWorkspace((s) => s.outline?.registryNames?.[BIOME_FOLDER] ?? NO_IDS)
  // The project's own biomes (its datapacks' too) by id, then the game's in full.
  const biomes = biomeChoices(projectBiomes, gameData?.registries?.[BIOME_REGISTRY] ?? NO_IDS)
  const customBlocks = useWorkspace((s) => s.outline?.resources.block ?? NO_IDS)
  const structures = useWorkspace((s) => s.outline?.resources.structure ?? NO_IDS)
  const gesture = {
    onGestureStart: () => workspace.getState().beginGesture(path),
    onGestureEnd: () => workspace.getState().endGesture(path),
  }
  const common = { edit, gesture, blocks, customBlocks, structures, biomes }
  return (
    <>
      <Datalist id={BLOCKS_LIST} values={blocks} />
      <Datalist id={BIOMES_LIST} values={biomes} />
      <TerrainSection model={model} {...common} />
      <LayersSection model={model} {...common} />
      <FloorSection model={model} {...common} />
      <CavesSection model={model} {...common} />
      <OresSection model={model} {...common} />
      <DecorationsSection model={model} {...common} />
      <BiomesSection model={model} {...common} />
      <StructuresSection model={model} {...common} />
      <ScriptSection path={path} model={model} {...common} />
    </>
  )
}

const NO_IDS: string[] = []

interface Parts {
  model: TerrainFile
  edit: Edit
  gesture: { onGestureStart: () => void; onGestureEnd: () => void }
  blocks: string[]
  customBlocks: string[]
  structures: string[]
  biomes: string[]
}

// ---- blocks ---------------------------------------------------------------------------------------

const KIND_LABELS: Record<BlockKind, string> = {
  block: "the game's",
  customBlock: "the project's",
  structure: 'a structure',
}

/** A select of [ids] that keeps a value it doesn't list (a missing one) and says when there's none to pick. */
function IdSelect({
  label,
  dataPath,
  value,
  ids,
  none,
  onChange,
}: {
  label: string
  dataPath: string
  value: string | undefined
  ids: string[]
  none: string
  onChange: (value: string) => void
}) {
  return (
    <SelectField
      label={label}
      dataPath={dataPath}
      value={value ?? ''}
      options={[
        ...ids.map((id) => ({ value: id, label: id })),
        ...(value && !ids.includes(value) ? [{ value, label: `${value} (missing)` }] : []),
        ...(ids.length === 0 && !value ? [{ value: '', label: none }] : []),
      ]}
      onChange={onChange}
    />
  )
}

/**
 * The block something places: a block state of the game's, one of the project's blocks (a plain cube), or, where
 * [kinds] has it, one of the project's structures. [fallback] is the game's block it starts as, and what an empty
 * one means when [optional].
 */
function BlockPicker({
  choice,
  at,
  kinds,
  fallback,
  optional = false,
  blocks,
  customBlocks,
  structures,
  onChange,
}: {
  choice: BlockChoice | undefined
  at: (string | number)[]
  kinds: readonly BlockKind[]
  fallback: string
  optional?: boolean
  blocks: string[]
  customBlocks: string[]
  structures: string[]
  onChange: (kind: BlockKind, value: string) => void
}) {
  const kind = blockKind(choice)
  const path = (key: string) => fieldPath([...at, key])
  return (
    <>
      <SelectField
        label="Block of"
        dataPath={path(kind)}
        value={kind}
        options={kinds.map((it) => ({ value: it, label: KIND_LABELS[it] }))}
        onChange={(value) => {
          const next = value as BlockKind
          onChange(
            next,
            next === 'block'
              ? optional
                ? ''
                : fallback
              : ((next === 'customBlock' ? customBlocks : structures)[0] ?? ''),
          )
        }}
      />
      {kind === 'block' && (
        <TextField
          label="Block"
          dataPath={path('block')}
          value={choice?.block}
          placeholder={optional ? fallback : undefined}
          list={blocks.length ? BLOCKS_LIST : undefined}
          onChange={(value) => onChange('block', value.trim())}
        />
      )}
      {kind === 'customBlock' && (
        <IdSelect
          label="Block"
          dataPath={path('customBlock')}
          value={choice?.customBlock}
          ids={customBlocks}
          none="(the project has no blocks)"
          onChange={(value) => onChange('customBlock', value)}
        />
      )}
      {kind === 'structure' && (
        <IdSelect
          label="Structure"
          dataPath={path('structure')}
          value={choice?.structure}
          ids={structures}
          none="(the project has no structures)"
          onChange={(value) => onChange('structure', value)}
        />
      )}
    </>
  )
}

/** Which of the file's biome areas an ore, a cave or a decoration keeps to: none ticked is all of them. */
function AreaFilter({
  model,
  at,
  areas,
  change,
}: {
  model: TerrainFile
  at: (string | number)[]
  areas: string[] | undefined
  change: (recipe: (entry: { biomes?: string[] }) => void) => void
}) {
  const names = namesOf(model, 'biomes')
  if (names.length < 2 && !areas?.length) return null
  return (
    <div data-path={fieldPath([...at, 'biomes'])}>
      <Muted>{areas?.length ? 'Only in' : 'In every biome area, or only in'}</Muted>
      {names.map((name) => (
        <CheckField
          key={name}
          label={name}
          value={areas?.includes(name) ?? false}
          onChange={(on) => change((entry) => setInArea(entry, name, on))}
        />
      ))}
    </div>
  )
}

/** Block ids as a comma-separated list; empty is none. */
function IdListField({
  label,
  dataPath,
  value,
  placeholder,
  onChange,
}: {
  label: string
  dataPath: string
  value: string[] | undefined
  placeholder: string
  onChange: (ids: string[] | undefined) => void
}) {
  return (
    <TextField
      label={label}
      dataPath={dataPath}
      value={(value ?? []).join(', ')}
      placeholder={placeholder}
      list={BLOCKS_LIST}
      onChange={(text) => {
        const ids = text
          .split(',')
          .map((it) => it.trim())
          .filter(Boolean)
        onChange(ids.length ? ids : undefined)
      }}
    />
  )
}

// ---- noise ----------------------------------------------------------------------------------------

/** The fields of one noise, at [at] (a JSON path in the file). */
function NoiseFields({
  noise,
  at,
  change,
  gesture,
}: {
  noise: NoiseDef
  at: (string | number)[]
  change: (recipe: (noise: NoiseDef) => void) => void
  gesture: Parts['gesture']
}) {
  const octaves = octavesOf(noise)
  const path = (key: string) => fieldPath([...at, key])
  return (
    <>
      <SelectField
        label="Type"
        dataPath={path('type')}
        value={noise.type ?? 'openSimplex2'}
        options={NoiseTypeValues}
        onChange={(value) =>
          change((d) =>
            setNoiseField(d, 'type', value === 'openSimplex2' ? undefined : (value as NoiseType)),
          )
        }
      />
      <NumberField
        label="Frequency"
        dataPath={path('frequency')}
        value={noise.frequency}
        placeholder={String(TERRAIN_DEFAULTS.noiseFrequency)}
        step={0.001}
        min={0}
        {...gesture}
        onChange={(value) => change((d) => setNoiseField(d, 'frequency', value))}
      />
      <NumberField
        label="Octaves"
        dataPath={path('octaves')}
        value={noise.octaves}
        placeholder="1"
        step={1}
        min={1}
        onChange={(value) => change((d) => setNoiseField(d, 'octaves', whole(value)))}
      />
      {octaves > 1 && (
        <>
          <SelectField
            label="Combined as"
            dataPath={path('fractal')}
            value={noise.fractal ?? 'fbm'}
            options={NoiseFractalValues}
            onChange={(value) =>
              change((d) =>
                setNoiseField(d, 'fractal', value === 'fbm' ? undefined : (value as NoiseFractal)),
              )
            }
          />
          <NumberField
            label="Lacunarity"
            dataPath={path('lacunarity')}
            value={noise.lacunarity}
            placeholder={String(TERRAIN_DEFAULTS.noiseLacunarity)}
            step={0.1}
            min={1}
            {...gesture}
            onChange={(value) => change((d) => setNoiseField(d, 'lacunarity', value))}
          />
          <NumberField
            label="Gain"
            dataPath={path('gain')}
            value={noise.gain}
            placeholder={String(TERRAIN_DEFAULTS.noiseGain)}
            step={0.05}
            min={0}
            {...gesture}
            onChange={(value) => change((d) => setNoiseField(d, 'gain', value))}
          />
        </>
      )}
    </>
  )
}

// ---- named entries --------------------------------------------------------------------------------

/** A named entry's header: its name, rename and delete. */
function EntryHeader({
  name,
  one,
  taken,
  onRename,
  onDelete,
}: {
  name: string
  one: string
  taken: string[]
  onRename: (to: string) => void
  onDelete: () => void
}) {
  return (
    <span style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
      <strong style={{ flex: 1 }}>{name}</strong>
      <IconButton
        icon="edit"
        label={`Rename ${one} ${name}`}
        size={14}
        onClick={async () => {
          const to = await ask.prompt({
            title: `Rename ${one}`,
            label: 'Name',
            initial: name,
            confirmLabel: 'Rename',
            validate: (value) =>
              nameProblem(value, value !== name && taken.includes(value), one, ID_NAMES),
          })
          if (to && to !== name) onRename(to)
        }}
      />
      <IconButton icon="trash" label={`Delete ${one} ${name}`} size={14} onClick={onDelete} />
    </span>
  )
}

/** The "add" button of a named collection's section. */
function AddButton({ one, onAdd }: { one: string; onAdd: () => void }) {
  return <IconButton icon="plus" label={`Add ${one}`} size={14} onClick={onAdd} />
}

// ---- terrain --------------------------------------------------------------------------------------

function TerrainSection({ model, edit, gesture, blocks }: Parts) {
  const terrain = model.terrain ?? {}
  const names = namesOf(model, 'noises')
  const setTerrain = (change: (terrain: NonNullable<TerrainFile['terrain']>) => void) =>
    edit((d) => {
      d.terrain ??= {}
      change(d.terrain)
      if (Object.keys(d.terrain).length === 0) delete d.terrain
    })
  const add = async () => {
    const name = await askName('terrain noise', names, names.length ? '' : 'hills')
    if (name) edit((d) => addEntry(d, 'noises', name, newEntry('noises', '')))
  }
  return (
    <Section title="Terrain" actions={<AddButton one="terrain noise" onAdd={add} />}>
      <NumberField
        label="Base height"
        dataPath="terrain.base"
        value={terrain.base}
        placeholder={String(TERRAIN_DEFAULTS.base)}
        step={1}
        {...gesture}
        onChange={(value) => setTerrain((t) => setKey(t, 'base', whole(value)))}
      />
      <NumberField
        label="Sea level"
        dataPath="terrain.seaLevel"
        value={terrain.seaLevel}
        placeholder={String(TERRAIN_DEFAULTS.seaLevel)}
        step={1}
        {...gesture}
        onChange={(value) => setTerrain((t) => setKey(t, 'seaLevel', whole(value)))}
      />
      <TextField
        label="Sea"
        dataPath="terrain.fluid"
        value={terrain.fluid}
        placeholder={TERRAIN_DEFAULTS.fluid}
        list={blocks.length ? BLOCKS_LIST : undefined}
        onChange={(value) => setTerrain((t) => setKey(t, 'fluid', value.trim() || undefined))}
      />
      <NumberField
        label="Blend borders over"
        dataPath="terrain.blend"
        value={terrain.blend}
        placeholder={String(TERRAIN_DEFAULTS.blend)}
        step={1}
        min={0}
        {...gesture}
        onChange={(value) => setTerrain((t) => setKey(t, 'blend', whole(value)))}
      />
      {names.length === 0 && <Muted>No noises: the ground is flat at the base height.</Muted>}
      <NoiseEntries
        collection="noises"
        noises={terrain.noises}
        at={['terrain', 'noises']}
        edit={edit}
        gesture={gesture}
      />
      <DensityFields model={model} edit={edit} gesture={gesture} />
    </Section>
  )
}

/** The ground made 3D (`terrain.density`): its 3D noises and the islands floating over it. */
function DensityFields({ model, edit, gesture }: Pick<Parts, 'model' | 'edit' | 'gesture'>) {
  const density = model.terrain?.density
  const names = Object.keys(density?.noises ?? {})
  const islands = density?.islands
  const at = ['terrain', 'density']
  const add = async () => {
    const name = await askName('3D noise', names, names.length ? '' : 'overhangs')
    if (name) edit((d) => addEntry(d, 'densityNoises', name, newEntry('densityNoises', '')))
  }
  const changeIslands = (recipe: (target: Islands) => void) =>
    edit((d) => {
      const target = d.terrain?.density?.islands
      if (target) recipe(target)
    })
  return (
    <div data-path={fieldPath(at)}>
      <CheckField
        label="3D ground (overhangs, arches, islands)"
        value={density !== undefined}
        onChange={(on) => edit((d) => setDensity(d, on))}
      />
      {density && (
        <>
          <Hint>
            Each 3D noise moves the ground&apos;s surface up or down by its amplitude, differently
            at each height, so it leans out over itself.
          </Hint>
          <NoiseEntries
            collection="densityNoises"
            noises={density.noises}
            at={[...at, 'noises']}
            edit={edit}
            gesture={gesture}
          />
          <Button size="small" icon="plus" onClick={add}>
            Add a 3D noise
          </Button>
          <CheckField
            label="Floating islands"
            value={islands !== undefined}
            onChange={(on) => edit((d) => setIslands(d, on))}
          />
          {islands && (
            <FieldGroup data-path={fieldPath([...at, 'islands'])} header={<strong>Islands</strong>}>
              <NumberField
                label="Height"
                dataPath={fieldPath([...at, 'islands', 'y'])}
                value={islands.y}
                placeholder={String(TERRAIN_DEFAULTS.islandsY)}
                step={1}
                {...gesture}
                onChange={(value) => changeIslands((i) => setKey(i, 'y', whole(value)))}
              />
              <NumberField
                label="Thickness"
                dataPath={fieldPath([...at, 'islands', 'thickness'])}
                value={islands.thickness}
                placeholder={String(TERRAIN_DEFAULTS.islandsThickness)}
                step={1}
                min={TERRAIN_LIMITS.minIslandsThickness}
                {...gesture}
                onChange={(value) => changeIslands((i) => setKey(i, 'thickness', whole(value)))}
              />
              <NumberField
                label="Threshold"
                dataPath={fieldPath([...at, 'islands', 'threshold'])}
                value={islands.threshold}
                placeholder={String(TERRAIN_DEFAULTS.islandsThreshold)}
                step={0.05}
                min={-1}
                {...gesture}
                onChange={(value) => changeIslands((i) => setKey(i, 'threshold', value))}
              />
              <AreaFilter
                model={model}
                at={[...at, 'islands']}
                areas={islands.biomes}
                change={changeIslands}
              />
              <Muted>Pattern</Muted>
              <NoiseFields
                noise={islands.noise ?? {}}
                at={[...at, 'islands', 'noise']}
                gesture={gesture}
                change={(recipe) =>
                  changeIslands((i) => {
                    i.noise ??= {}
                    recipe(i.noise)
                  })
                }
              />
            </FieldGroup>
          )}
        </>
      )}
    </div>
  )
}

/**
 * Named terrain noises, each its amplitude and pattern: the file's, or a biome area's own; or (a `densityNoises`
 * collection, or an area's with `density`) 3D noises, with their squash too.
 */
function NoiseEntries({
  collection,
  noises,
  at,
  edit,
  gesture,
}: {
  collection: 'noises' | 'densityNoises' | AreaNoises
  noises: Record<string, { amplitude?: number; squash?: number; noise?: NoiseDef }> | undefined
  at: string[]
  edit: Edit
  gesture: Parts['gesture']
}) {
  const names = Object.keys(noises ?? {})
  const threeD =
    collection === 'densityNoises' || (typeof collection === 'object' && !!collection.density)
  const one = threeD ? '3D noise' : 'terrain noise'
  return (
    <>
      {names.map((name) => {
        const entry = noises?.[name]
        if (!entry) return null
        const here = [...at, name]
        return (
          <FieldGroup
            key={name}
            data-path={fieldPath(here)}
            header={
              <EntryHeader
                name={name}
                one={one}
                taken={names}
                onRename={(to) => edit((d) => renameEntry(d, collection, name, to))}
                onDelete={() => edit((d) => deleteEntry(d, collection, name))}
              />
            }
          >
            <NumberField
              label="Amplitude"
              dataPath={fieldPath([...here, 'amplitude'])}
              value={entry.amplitude}
              placeholder={String(TERRAIN_DEFAULTS.amplitude)}
              step={1}
              {...gesture}
              onChange={(value) =>
                edit((d) => {
                  const target = noiseEntry(d, collection, name)
                  if (target) setKey(target, 'amplitude', value)
                })
              }
            />
            {threeD && (
              <NumberField
                label="Squash"
                dataPath={fieldPath([...here, 'squash'])}
                value={entry.squash}
                placeholder={String(TERRAIN_DEFAULTS.squash)}
                step={0.1}
                min={0.05}
                {...gesture}
                onChange={(value) =>
                  edit((d) => {
                    const target = noiseEntry(d, collection, name)
                    if (target) setKey(target, 'squash', value)
                  })
                }
              />
            )}
            <NoiseFields
              noise={entry.noise ?? {}}
              at={[...here, 'noise']}
              gesture={gesture}
              change={(recipe) =>
                edit((d) => {
                  const target = noiseEntry(d, collection, name)
                  if (!target) return
                  target.noise ??= {}
                  recipe(target.noise)
                })
              }
            />
          </FieldGroup>
        )
      })}
    </>
  )
}

// ---- layers ---------------------------------------------------------------------------------------

/** One list of layers, from the surface down. */
function LayerRows({
  model,
  at,
  edit,
  blocks,
  customBlocks,
  structures,
  gesture,
  base,
}: Parts & { at: LayerList; base: (string | number)[] }) {
  const layers = layersAt(model, at)
  return (
    <>
      {layers.map((layer, index) => (
        <FieldGroup
          key={index}
          data-path={fieldPath([...base, index])}
          header={<strong>Layer {index + 1}</strong>}
        >
          <BlockPicker
            choice={layer}
            at={[...base, index]}
            kinds={GROUND_KINDS}
            fallback={TERRAIN_DEFAULTS.stone}
            blocks={blocks}
            customBlocks={customBlocks}
            structures={structures}
            onChange={(kind, value) =>
              edit((d) => setLayer(d, at, index, { block: [kind, value] }))
            }
          />
          <NumberField
            label="Thickness"
            dataPath={fieldPath([...base, index, 'thickness'])}
            value={layer.thickness}
            placeholder="1"
            step={1}
            min={1}
            {...gesture}
            onChange={(value) => edit((d) => setLayer(d, at, index, { thickness: whole(value) }))}
          />
          <span style={{ display: 'flex', gap: 4 }}>
            <IconButton
              icon="arrow"
              label={`Move layer ${index + 1} up`}
              size={14}
              disabled={index === 0}
              style={{ transform: 'rotate(-90deg)' }}
              onClick={() => edit((d) => moveLayer(d, at, index, index - 1))}
            />
            <IconButton
              icon="arrow"
              label={`Move layer ${index + 1} down`}
              size={14}
              disabled={index === layers.length - 1}
              style={{ transform: 'rotate(90deg)' }}
              onClick={() => edit((d) => moveLayer(d, at, index, index + 1))}
            />
            <IconButton
              icon="trash"
              label={`Delete layer ${index + 1}`}
              size={14}
              onClick={() => edit((d) => removeLayer(d, at, index))}
            />
          </span>
        </FieldGroup>
      ))}
      {layers.length < TERRAIN_LIMITS.maxLayers && (
        <Button
          size="small"
          icon="plus"
          onClick={() => edit((d) => addLayer(d, at, TERRAIN_DEFAULTS.stone))}
        >
          Add a layer
        </Button>
      )}
    </>
  )
}

/** What the ground can be made of: blocks of the game's or the project's (plain cubes). */
const GROUND_KINDS: readonly BlockKind[] = ['block', 'customBlock']

function LayersSection(parts: Parts) {
  const { model, edit, blocks, customBlocks, structures } = parts
  return (
    <Section title="Ground">
      <Hint>Layers from the surface down, then stone all the way.</Hint>
      <LayerRows {...parts} at={{ key: 'layers' }} base={['layers']} />
      <FieldGroup data-path="stone" header={<strong>Then, all the way down</strong>}>
        <BlockPicker
          choice={model.stone}
          at={['stone']}
          kinds={GROUND_KINDS}
          fallback={TERRAIN_DEFAULTS.stone}
          optional
          blocks={blocks}
          customBlocks={customBlocks}
          structures={structures}
          onChange={(kind, value) => edit((d) => setStone(d, kind, value))}
        />
      </FieldGroup>
      <Muted>Under the sea, the ground is:</Muted>
      <LayerRows {...parts} at={{ key: 'underwater' }} base={['underwater']} />
    </Section>
  )
}

function FloorSection({ model, edit, blocks, customBlocks, structures, gesture }: Parts) {
  const floor = model.floor
  return (
    <Section
      title="Floor"
      enabled={floor !== undefined}
      onToggle={(on) =>
        edit((d) => {
          if (on) d.floor = {}
          else delete d.floor
        })
      }
    >
      <BlockPicker
        choice={floor}
        at={['floor']}
        kinds={GROUND_KINDS}
        fallback={TERRAIN_DEFAULTS.floorBlock}
        optional
        blocks={blocks}
        customBlocks={customBlocks}
        structures={structures}
        onChange={(kind, value) =>
          edit((d) => {
            if (d.floor) setBlock(d.floor, kind, value)
          })
        }
      />
      <NumberField
        label="Thickness"
        dataPath="floor.thickness"
        value={floor?.thickness}
        placeholder={String(TERRAIN_DEFAULTS.floorThickness)}
        step={1}
        min={1}
        {...gesture}
        onChange={(value) =>
          edit((d) => {
            if (d.floor) setKey(d.floor, 'thickness', whole(value))
          })
        }
      />
    </Section>
  )
}

// ---- caves ----------------------------------------------------------------------------------------

function CavesSection({ model, edit, gesture }: Parts) {
  const names = namesOf(model, 'caves')
  const add = async () => {
    const name = await askName('cave', names, names.length ? '' : 'caverns')
    if (name) edit((d) => addEntry(d, 'caves', name, newEntry('caves', '')))
  }
  return (
    <Section title="Caves" actions={<AddButton one="cave" onAdd={add} />}>
      {names.length === 0 && <Muted>No caves: the ground is solid.</Muted>}
      {names.map((name) => {
        const cave = model.caves?.[name]
        if (!cave) return null
        const at = ['caves', name]
        const change = (recipe: (cave: NonNullable<TerrainFile['caves']>[string]) => void) =>
          edit((d) => {
            const target = d.caves?.[name]
            if (target) recipe(target)
          })
        const type = cave.type ?? 'cheese'
        return (
          <FieldGroup
            key={name}
            data-path={fieldPath(at)}
            header={
              <EntryHeader
                name={name}
                one="cave"
                taken={names}
                onRename={(to) => edit((d) => renameEntry(d, 'caves', name, to))}
                onDelete={() => edit((d) => deleteEntry(d, 'caves', name))}
              />
            }
          >
            <SelectField
              label="Shape"
              dataPath={fieldPath([...at, 'type'])}
              value={type}
              options={CaveTypeValues}
              onChange={(value) =>
                change((c) =>
                  setKey(c, 'type', value === 'cheese' ? undefined : (value as CaveType)),
                )
              }
            />
            <NumberField
              label="Threshold"
              dataPath={fieldPath([...at, 'threshold'])}
              value={cave.threshold}
              placeholder={String(
                type === 'cheese' ? TERRAIN_DEFAULTS.caveCheese : TERRAIN_DEFAULTS.caveSpaghetti,
              )}
              step={0.01}
              {...gesture}
              onChange={(value) => change((c) => setKey(c, 'threshold', value))}
            />
            <NumberField
              label="Lowest"
              dataPath={fieldPath([...at, 'minY'])}
              value={cave.minY}
              placeholder={String(TERRAIN_DEFAULTS.caveMinY)}
              step={1}
              {...gesture}
              onChange={(value) => change((c) => setKey(c, 'minY', whole(value)))}
            />
            <NumberField
              label="Highest"
              dataPath={fieldPath([...at, 'maxY'])}
              value={cave.maxY}
              placeholder={String(TERRAIN_DEFAULTS.caveMaxY)}
              step={1}
              {...gesture}
              onChange={(value) => change((c) => setKey(c, 'maxY', whole(value)))}
            />
            <NumberField
              label="Below the surface"
              dataPath={fieldPath([...at, 'depth'])}
              value={cave.depth}
              placeholder={String(TERRAIN_DEFAULTS.caveDepth)}
              step={1}
              min={0}
              {...gesture}
              onChange={(value) => change((c) => setKey(c, 'depth', whole(value)))}
            />
            <AreaFilter model={model} at={at} areas={cave.biomes} change={change} />
            <Muted>Pattern</Muted>
            <NoiseFields
              noise={cave.noise ?? {}}
              at={[...at, 'noise']}
              gesture={gesture}
              change={(recipe) =>
                change((c) => {
                  c.noise ??= {}
                  recipe(c.noise)
                })
              }
            />
          </FieldGroup>
        )
      })}
    </Section>
  )
}

// ---- ores -----------------------------------------------------------------------------------------

function OresSection({ model, edit, gesture, blocks, customBlocks }: Parts) {
  const stone = model.stone?.block ?? model.stone?.customBlock ?? TERRAIN_DEFAULTS.stone
  const names = namesOf(model, 'ores')
  const add = async () => {
    const name = await askName('ore', names, names.length ? '' : 'iron')
    if (name)
      edit((d) =>
        addEntry(d, 'ores', name, newEntry('ores', firstOre(blocks, TERRAIN_DEFAULTS.stone))),
      )
  }
  return (
    <Section title="Ores" actions={<AddButton one="ore" onAdd={add} />}>
      {names.length === 0 && <Muted>No ores.</Muted>}
      {names.map((name) => {
        const ore = model.ores?.[name]
        if (!ore) return null
        const at = ['ores', name]
        const change = (recipe: (ore: NonNullable<TerrainFile['ores']>[string]) => void) =>
          edit((d) => {
            const target = d.ores?.[name]
            if (target) recipe(target)
          })
        return (
          <FieldGroup
            key={name}
            data-path={fieldPath(at)}
            header={
              <EntryHeader
                name={name}
                one="ore"
                taken={names}
                onRename={(to) => edit((d) => renameEntry(d, 'ores', name, to))}
                onDelete={() => edit((d) => deleteEntry(d, 'ores', name))}
              />
            }
          >
            <BlockPicker
              choice={ore}
              at={at}
              kinds={GROUND_KINDS}
              fallback={TERRAIN_DEFAULTS.stone}
              blocks={blocks}
              customBlocks={customBlocks}
              structures={NO_IDS}
              onChange={(kind, value) => change((o) => setBlock(o, kind, value))}
            />
            <IdListField
              label="Replaces"
              dataPath={fieldPath([...at, 'replace'])}
              value={ore.replace}
              placeholder={stone}
              onChange={(ids) => change((o) => setKey(o, 'replace', ids))}
            />
            <NumberField
              label="Vein size"
              dataPath={fieldPath([...at, 'size'])}
              value={ore.size}
              placeholder={String(TERRAIN_DEFAULTS.oreSize)}
              step={1}
              min={1}
              {...gesture}
              onChange={(value) => change((o) => setKey(o, 'size', whole(value)))}
            />
            <NumberField
              label="Veins per chunk"
              dataPath={fieldPath([...at, 'veins'])}
              value={ore.veins}
              placeholder={String(TERRAIN_DEFAULTS.oreVeins)}
              step={1}
              min={0}
              {...gesture}
              onChange={(value) => change((o) => setKey(o, 'veins', whole(value)))}
            />
            <NumberField
              label="Lowest"
              dataPath={fieldPath([...at, 'minY'])}
              value={ore.minY}
              placeholder={String(TERRAIN_DEFAULTS.oreMinY)}
              step={1}
              {...gesture}
              onChange={(value) => change((o) => setKey(o, 'minY', whole(value)))}
            />
            <NumberField
              label="Highest"
              dataPath={fieldPath([...at, 'maxY'])}
              value={ore.maxY}
              placeholder={String(TERRAIN_DEFAULTS.oreMaxY)}
              step={1}
              {...gesture}
              onChange={(value) => change((o) => setKey(o, 'maxY', whole(value)))}
            />
            <SelectField
              label="Spread"
              dataPath={fieldPath([...at, 'distribution'])}
              value={ore.distribution ?? 'uniform'}
              options={OreDistributionValues}
              onChange={(value) =>
                change((o) =>
                  setKey(
                    o,
                    'distribution',
                    value === 'uniform' ? undefined : (value as OreDistribution),
                  ),
                )
              }
            />
            <AreaFilter model={model} at={at} areas={ore.biomes} change={change} />
          </FieldGroup>
        )
      })}
    </Section>
  )
}

// ---- decorations ----------------------------------------------------------------------------------

/** What a decoration places: a block of the game's or the project's, or a structure. */
const DECORATION_KINDS: readonly BlockKind[] = ['block', 'customBlock', 'structure']

const PLACEMENT_LABELS: Record<DecorationPlacement, string> = {
  surface: 'on the ground',
  underwater: 'on the sea floor',
  underground: 'in the ground',
  caveFloor: "on a cave's floor",
  caveCeiling: "from a cave's ceiling",
}

/** Where a structure may go: what a column alone decides, so every chunk it reaches places it alike. */
const STRUCTURE_PLACEMENTS: readonly DecorationPlacement[] = [
  'surface',
  'underwater',
  'underground',
]

function DecorationsSection({ model, edit, gesture, blocks, customBlocks, structures }: Parts) {
  const names = namesOf(model, 'decorations')
  const add = async () => {
    const name = await askName('decoration', names, names.length ? '' : 'rocks')
    if (name)
      edit((d) => addEntry(d, 'decorations', name, newEntry('decorations', TERRAIN_DEFAULTS.stone)))
  }
  return (
    <Section title="Decorations" actions={<AddButton one="decoration" onAdd={add} />}>
      {names.length === 0 && <Muted>No decorations: nothing is scattered on the ground.</Muted>}
      {names.map((name) => {
        const decoration = model.decorations?.[name]
        if (!decoration) return null
        const at = ['decorations', name]
        const change = (
          recipe: (decoration: NonNullable<TerrainFile['decorations']>[string]) => void,
        ) =>
          edit((d) => {
            const target = d.decorations?.[name]
            if (target) recipe(target)
          })
        const structure = blockKind(decoration) === 'structure'
        const placement = decoration.placement ?? 'surface'
        const placements = structure ? STRUCTURE_PLACEMENTS : DecorationPlacementValues
        return (
          <FieldGroup
            key={name}
            data-path={fieldPath(at)}
            header={
              <EntryHeader
                name={name}
                one="decoration"
                taken={names}
                onRename={(to) => edit((d) => renameEntry(d, 'decorations', name, to))}
                onDelete={() => edit((d) => deleteEntry(d, 'decorations', name))}
              />
            }
          >
            <BlockPicker
              choice={decoration}
              at={at}
              kinds={DECORATION_KINDS}
              fallback={TERRAIN_DEFAULTS.stone}
              blocks={blocks}
              customBlocks={customBlocks}
              structures={structures}
              onChange={(kind, value) =>
                change((it) => {
                  setBlock(it, kind, value)
                  if (kind !== 'structure') delete it.rotate
                  else if (it.placement && !STRUCTURE_PLACEMENTS.includes(it.placement))
                    delete it.placement
                })
              }
            />
            <SelectField
              label="Placed"
              dataPath={fieldPath([...at, 'placement'])}
              value={placement}
              options={placements.map((it) => ({ value: it, label: PLACEMENT_LABELS[it] }))}
              onChange={(value) =>
                change((it) =>
                  setKey(
                    it,
                    'placement',
                    value === 'surface' ? undefined : (value as DecorationPlacement),
                  ),
                )
              }
            />
            <NumberField
              label="Tries per chunk"
              dataPath={fieldPath([...at, 'count'])}
              value={decoration.count}
              placeholder={String(TERRAIN_DEFAULTS.decorationCount)}
              step={1}
              min={0}
              {...gesture}
              onChange={(value) => change((it) => setKey(it, 'count', whole(value)))}
            />
            <NumberField
              label="Chance"
              dataPath={fieldPath([...at, 'chance'])}
              value={decoration.chance}
              placeholder="1"
              step={0.05}
              min={0}
              {...gesture}
              onChange={(value) => change((it) => setKey(it, 'chance', value))}
            />
            <CheckField
              label="In patches"
              dataPath={fieldPath([...at, 'noise'])}
              value={decoration.noise !== undefined}
              onChange={(on) =>
                change((it) => {
                  if (on) it.noise = { frequency: 0.03 }
                  else {
                    delete it.noise
                    delete it.threshold
                  }
                })
              }
            />
            {decoration.noise && (
              <>
                <NumberField
                  label="Patches from"
                  dataPath={fieldPath([...at, 'threshold'])}
                  value={decoration.threshold}
                  placeholder="0"
                  step={0.05}
                  min={-1}
                  {...gesture}
                  onChange={(value) => change((it) => setKey(it, 'threshold', value))}
                />
                <NoiseFields
                  noise={decoration.noise}
                  at={[...at, 'noise']}
                  gesture={gesture}
                  change={(recipe) =>
                    change((it) => {
                      it.noise ??= {}
                      recipe(it.noise)
                    })
                  }
                />
              </>
            )}
            <IdListField
              label={
                placement === 'underground'
                  ? 'Replaces'
                  : placement === 'caveCeiling'
                    ? 'Hangs from'
                    : 'On'
              }
              dataPath={fieldPath([...at, 'on'])}
              value={decoration.on}
              placeholder={placement === 'underground' ? 'the stone' : 'any ground'}
              onChange={(ids) => change((it) => setKey(it, 'on', ids))}
            />
            <NumberField
              label="Lowest"
              dataPath={fieldPath([...at, 'minY'])}
              value={decoration.minY}
              placeholder="the bottom"
              step={1}
              {...gesture}
              onChange={(value) => change((it) => setKey(it, 'minY', whole(value)))}
            />
            <NumberField
              label="Highest"
              dataPath={fieldPath([...at, 'maxY'])}
              value={decoration.maxY}
              placeholder="the top"
              step={1}
              {...gesture}
              onChange={(value) => change((it) => setKey(it, 'maxY', whole(value)))}
            />
            {structure && (
              <CheckField
                label="Turned at random"
                dataPath={fieldPath([...at, 'rotate'])}
                value={decoration.rotate ?? true}
                onChange={(on) => change((it) => setKey(it, 'rotate', on ? undefined : false))}
              />
            )}
            <AreaFilter model={model} at={at} areas={decoration.biomes} change={change} />
          </FieldGroup>
        )
      })}
    </Section>
  )
}

// ---- biomes ---------------------------------------------------------------------------------------

function RangeFields({
  label,
  at,
  range,
  change,
  gesture,
}: {
  label: string
  at: (string | number)[]
  range: ClimateRange | undefined
  change: (range: ClimateRange | undefined) => void
  gesture: Parts['gesture']
}) {
  const set = (key: 'min' | 'max', value: number | undefined) => {
    const next: ClimateRange = { ...range }
    if (value === undefined) delete next[key]
    else next[key] = value
    change(Object.keys(next).length === 0 ? undefined : next)
  }
  return (
    <>
      <NumberField
        label={`${label} from`}
        dataPath={fieldPath([...at, 'min'])}
        value={range?.min}
        placeholder="-1"
        step={0.05}
        {...gesture}
        onChange={(value) => set('min', value)}
      />
      <NumberField
        label={`${label} to`}
        dataPath={fieldPath([...at, 'max'])}
        value={range?.max}
        placeholder="1"
        step={0.05}
        {...gesture}
        onChange={(value) => set('max', value)}
      />
    </>
  )
}

function BiomesSection(parts: Parts) {
  const { model, edit, gesture, biomes } = parts
  const names = namesOf(model, 'biomes')
  const add = async () => {
    const name = await askName('biome area', names, names.length ? '' : 'plains')
    if (name) edit((d) => addEntry(d, 'biomes', name, newEntry('biomes', '')))
  }
  const climate = model.climate ?? {}
  const setClimate = (key: 'temperature' | 'humidity', recipe: (noise: NoiseDef) => void) =>
    edit((d) => {
      d.climate ??= {}
      d.climate[key] ??= {}
      recipe(d.climate[key]!)
    })
  return (
    <Section title="Biomes" actions={<AddButton one="biome area" onAdd={add} />}>
      {names.length === 0 && (
        <Muted>No biome areas: all of the world is {TERRAIN_DEFAULTS.biome}.</Muted>
      )}
      {names.map((name) => {
        const area = model.biomes?.[name]
        if (!area) return null
        const at = ['biomes', name]
        const change = (recipe: (area: NonNullable<TerrainFile['biomes']>[string]) => void) =>
          edit((d) => {
            const target = d.biomes?.[name]
            if (target) recipe(target)
          })
        return (
          <FieldGroup
            key={name}
            data-path={fieldPath(at)}
            header={
              <EntryHeader
                name={name}
                one="biome area"
                taken={names}
                onRename={(to) => edit((d) => renameEntry(d, 'biomes', name, to))}
                onDelete={() => edit((d) => deleteEntry(d, 'biomes', name))}
              />
            }
          >
            <TextField
              label="Biome"
              dataPath={fieldPath([...at, 'biome'])}
              value={area.biome}
              list={biomes.length ? BIOMES_LIST : undefined}
              onChange={(value) => change((a) => (a.biome = value.trim()))}
            />
            <RangeFields
              label="Temperature"
              at={[...at, 'temperature']}
              range={area.temperature}
              gesture={gesture}
              change={(range) => change((a) => setKey(a, 'temperature', range))}
            />
            <RangeFields
              label="Humidity"
              at={[...at, 'humidity']}
              range={area.humidity}
              gesture={gesture}
              change={(range) => change((a) => setKey(a, 'humidity', range))}
            />
            <AreaLayers {...parts} name={name} area={area} />
            <AreaTerrainFields
              name={name}
              area={area}
              threeD={model.terrain?.density !== undefined}
              edit={edit}
              gesture={gesture}
            />
          </FieldGroup>
        )
      })}
      <Muted>Climate noise</Muted>
      <Hint>Where each biome is found: each area is the one whose ranges a place fits best.</Hint>
      {(['temperature', 'humidity'] as const).map((key) => (
        <FieldGroup
          key={key}
          header={<strong>{key === 'temperature' ? 'Temperature' : 'Humidity'}</strong>}
        >
          <NoiseFields
            noise={climate[key] ?? {}}
            at={['climate', key]}
            gesture={gesture}
            change={(recipe) => setClimate(key, recipe)}
          />
        </FieldGroup>
      ))}
      <FieldGroup data-path="climate.jitter" header={<strong>Borders</strong>}>
        <Hint>How far the borders between areas wander, so they aren&apos;t smooth curves.</Hint>
        <NumberField
          label="Wander"
          dataPath="climate.jitter.amplitude"
          value={climate.jitter?.amplitude}
          placeholder={String(TERRAIN_DEFAULTS.jitterAmplitude)}
          step={1}
          min={0}
          {...gesture}
          onChange={(value) =>
            edit((d) => {
              d.climate ??= {}
              d.climate.jitter ??= {}
              setKey(d.climate.jitter, 'amplitude', value)
              if (Object.keys(d.climate.jitter).length === 0) delete d.climate.jitter
              if (Object.keys(d.climate).length === 0) delete d.climate
            })
          }
        />
        <NoiseFields
          noise={climate.jitter?.noise ?? {}}
          at={['climate', 'jitter', 'noise']}
          gesture={gesture}
          change={(recipe) =>
            edit((d) => {
              d.climate ??= {}
              d.climate.jitter ??= {}
              d.climate.jitter.noise ??= {}
              recipe(d.climate.jitter.noise)
            })
          }
        />
      </FieldGroup>
    </Section>
  )
}

/** A biome area's own layers, if it has them. */
function AreaLayers({
  name,
  area,
  ...parts
}: Parts & { name: string; area: NonNullable<TerrainFile['biomes']>[string] }) {
  const { edit } = parts
  return (
    <>
      {(['layers', 'underwater'] as const).map((key) => (
        <div key={key}>
          <CheckField
            label={key === 'layers' ? 'Own ground layers' : 'Own sea floor layers'}
            dataPath={fieldPath(['biomes', name, key])}
            value={area[key] !== undefined}
            onChange={(own) => edit((d) => setAreaOwnLayers(d, name, key, own))}
          />
          {area[key] !== undefined && (
            <LayerRows {...parts} at={{ area: name, key }} base={['biomes', name, key]} />
          )}
        </div>
      ))}
    </>
  )
}

/**
 * A biome area's own terrain, if it has one: its base height, the file's noises scaled, and noises of its own; in a
 * file with a 3D ground ([threeD]), its own 3D noises too.
 */
function AreaTerrainFields({
  name,
  area,
  threeD,
  edit,
  gesture,
}: {
  name: string
  area: NonNullable<TerrainFile['biomes']>[string]
  threeD: boolean
  edit: Edit
  gesture: Parts['gesture']
}) {
  const terrain = area.terrain
  const at = ['biomes', name, 'terrain']
  const collection: Collection = { area: name }
  const densityCollection: AreaNoises = { area: name, density: true }
  const names = Object.keys(terrain?.noises ?? {})
  const densityNames = Object.keys(terrain?.density?.noises ?? {})
  const addDensity = async () => {
    const noise = await askName('3D noise', densityNames, densityNames.length ? '' : 'arches')
    if (noise) edit((d) => addEntry(d, densityCollection, noise, newEntry(densityCollection, '')))
  }
  const change = (recipe: (terrain: NonNullable<typeof area.terrain>) => void) =>
    edit((d) => {
      const target = d.biomes?.[name]?.terrain
      if (target) recipe(target)
    })
  const add = async () => {
    const noise = await askName('terrain noise', names, names.length ? '' : 'peaks')
    if (noise) edit((d) => addEntry(d, collection, noise, newEntry(collection, '')))
  }
  return (
    <div data-path={fieldPath(at)}>
      <CheckField
        label="Own terrain"
        value={terrain !== undefined}
        onChange={(own) =>
          edit((d) => {
            const target = d.biomes?.[name]
            if (!target) return
            if (own) target.terrain = {}
            else delete target.terrain
          })
        }
      />
      {terrain && (
        <>
          <NumberField
            label="Its base height"
            dataPath={fieldPath([...at, 'base'])}
            value={terrain.base}
            placeholder="the file's"
            step={1}
            {...gesture}
            onChange={(value) => change((t) => setKey(t, 'base', whole(value)))}
          />
          <NumberField
            label="Noises times"
            dataPath={fieldPath([...at, 'scale'])}
            value={terrain.scale}
            placeholder="1"
            step={0.1}
            min={0}
            {...gesture}
            onChange={(value) => change((t) => setKey(t, 'scale', value))}
          />
          <NoiseEntries
            collection={collection}
            noises={terrain.noises}
            at={[...at, 'noises']}
            edit={edit}
            gesture={gesture}
          />
          <Button size="small" icon="plus" onClick={add}>
            Add a noise of its own
          </Button>
          {(threeD || terrain.density) && (
            <CheckField
              label="Own 3D noises"
              dataPath={fieldPath([...at, 'density'])}
              value={terrain.density !== undefined}
              onChange={(own) => edit((d) => setAreaDensity(d, name, own))}
            />
          )}
          {terrain.density && (
            <>
              <NumberField
                label="3D noises times"
                dataPath={fieldPath([...at, 'density', 'scale'])}
                value={terrain.density.scale}
                placeholder="1"
                step={0.1}
                min={0}
                {...gesture}
                onChange={(value) =>
                  change((t) => {
                    if (t.density) setKey(t.density, 'scale', value)
                  })
                }
              />
              <NoiseEntries
                collection={densityCollection}
                noises={terrain.density.noises}
                at={[...at, 'density', 'noises']}
                edit={edit}
                gesture={gesture}
              />
              <Button size="small" icon="plus" onClick={addDensity}>
                Add a 3D noise of its own
              </Button>
            </>
          )}
        </>
      )}
    </div>
  )
}

// ---- structures -----------------------------------------------------------------------------------

function StructuresSection({ model, edit }: Parts) {
  return (
    <Section title="Structures">
      <CheckField
        label="The game's structures"
        dataPath="structures.vanilla"
        value={model.structures?.vanilla ?? false}
        onChange={(value) =>
          edit((d) => {
            if (value) d.structures = { vanilla: true }
            else delete d.structures
          })
        }
      />
      <Hint>
        Villages, ruins and the project's structures that generate, where the biomes allow them.
      </Hint>
    </Section>
  )
}

// ---- script ---------------------------------------------------------------------------------------

/**
 * The file's Lua stages: whether it hands them to `terrain/<id>.lua` (made from format's template when it isn't
 * there), the budget each call gets, the noises the script asks for and the blocks it places besides the file's.
 */
function ScriptSection({ path, model, edit, gesture }: Parts & { path: string }) {
  const { workspace } = useApp()
  const files = useWorkspace((s) => s.files)
  const script = model.script
  const file = path.replace(/\.json$/, '.lua')
  const names = Object.keys(script?.noises ?? {})
  const turn = async (on: boolean) => {
    edit((d) => setScripted(d, on))
    if (on && !files.includes(file)) await workspace.getState().createFile(file, newTerrainScript())
  }
  const addNoise = async () => {
    const name = await askName('script noise', names, names.length ? '' : 'detail')
    if (name) edit((d) => addEntry(d, 'scriptNoises', name, newEntry('scriptNoises', '')))
  }
  return (
    <Section
      title="Script"
      actions={script ? <AddButton one="script noise" onAdd={addNoise} /> : undefined}
    >
      <CheckField
        label="Hand stages to a script"
        dataPath="script"
        value={script != null}
        onChange={(on) => void turn(on)}
      />
      {!script ? (
        <Hint>
          A Lua script beside the file (its height, terrain and decorate stages) shapes what the
          file makes, on the server and in this preview alike.
        </Hint>
      ) : (
        <>
          <Button icon="code" onClick={() => void workspace.getState().openFile(file)}>
            Open {file.replace(/^.*\//, '')}
          </Button>
          <NumberField
            label="Budget"
            dataPath="script.budget"
            value={script.budget}
            placeholder={String(TERRAIN_DEFAULTS.scriptBudget)}
            min={TERRAIN_LIMITS.minScriptBudget}
            step={1000}
            {...gesture}
            onChange={(value) => edit((d) => d.script && setKey(d.script, 'budget', whole(value)))}
          />
          <IdListField
            label="Game blocks it places"
            dataPath="script.blocks"
            value={script.blocks}
            placeholder="minecraft:cobblestone"
            onChange={(ids) => edit((d) => setScriptBlocks(d, 'blocks', ids))}
          />
          <IdListField
            label="Project blocks it places"
            dataPath="script.customBlocks"
            value={script.customBlocks}
            placeholder="ruby_ore"
            onChange={(ids) => edit((d) => setScriptBlocks(d, 'customBlocks', ids))}
          />
          {names.map((name) => {
            const here = ['script', 'noises', name]
            return (
              <FieldGroup
                key={name}
                data-path={fieldPath(here)}
                header={
                  <EntryHeader
                    name={name}
                    one="script noise"
                    taken={names}
                    onRename={(to) => edit((d) => renameEntry(d, 'scriptNoises', name, to))}
                    onDelete={() => edit((d) => deleteEntry(d, 'scriptNoises', name))}
                  />
                }
              >
                <NoiseFields
                  noise={script.noises?.[name] ?? {}}
                  at={here}
                  gesture={gesture}
                  change={(recipe) =>
                    edit((d) => {
                      const target = d.script?.noises?.[name]
                      if (target) recipe(target)
                    })
                  }
                />
              </FieldGroup>
            )
          })}
        </>
      )}
    </Section>
  )
}
