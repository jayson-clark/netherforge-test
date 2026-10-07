/**
 * A terrain (`terrain/<id>.json`, docs/format/terrain.md): a live preview of the world it makes (a map from
 * above and a slice through the ground, drawn by format's own generator, the server's code, for a seed) and, in the
 * inspector, every part of the file. The preview is drawn again whenever the file changes, from the file as it is,
 * in a worker of its own (`worker/`), with the project structures its decorations place read from their files and,
 * for a file with a `script`, the Lua it runs (`terrain/<id>.lua` and the modules, as the editor holds them: an
 * open script's unsaved text included), so a change to the script redraws it too.
 */
import { useEffect, useMemo, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react'
import {
  MANIFEST_FILE,
  TERRAIN_DEFAULTS,
  type DimensionTypeFile,
  type ProjectManifest,
  type TerrainFile,
  type TerrainMap,
  type TerrainPreviewResult,
  type TerrainScriptError,
  type TerrainSlice,
  type TerrainStructureInput,
} from '@/core/format'
import { readProjectBytes } from '@/core/backend/projectBytes'
import { mainFileOf, resourceIdsOf } from '@/core/paths'
import { isModel } from '@/core/store/documents'
import { currentText, parseCached } from '@/minecraft/item/projectItems'
import { useHomeNamespace } from '@/state/useResourcePacks'
import { readNbt } from '@netherforge/terrain-preview/nbt'
import { parseStructure } from '@netherforge/terrain-preview/structure'
import { useApp, useWorkspace } from '@/state/providers'
import { useAsyncResult } from '@/ui/useAsync'
import { InspectorPanel } from '@/editors/shared/docks'
import { EditorBar, EditorScreen, Stage } from '@/editors/shared/EditorLayout'
import { EditAsJson, useModelDoc } from '@/editors/shared/modelDoc'
import { RawDocView } from '@/editors/shared/RawDocView'
import { useFocusRequests } from '@/editors/shared/useFocusRequests'
import { useView, useViewState } from '@/editors/views'
import { Button } from '@/ui/Button'
import { NumberInput, SelectField } from '@/ui/fields'
import { Empty, Hint, Muted } from '@/ui/text'
import { heightChoices, pickHeights, type HeightChoice } from './heights'
import { TerrainInspector } from './Inspector'
import {
  areaColor,
  blockAt,
  decorationColor,
  decorationsAt,
  hasScript,
  mapPixels,
  place,
  slicePixels,
  sliceTop,
  sliceColors,
  scriptPaths,
  structureInput,
  structuresNamed,
} from '@netherforge/terrain-preview'
import { useTerrainPreview } from './worker/client'
import type { PreviewRequest } from './worker/core'
import styles from './TerrainEditor.module.css'

/**
 * What the preview draws: a world of the heights picked (`heights.ts`: the overworld's, or those of the dimension a
 * `netherforge.json` world naming this terrain has), a map of CELLS by CELLS cells, a slice WIDTH blocks wide.
 */
const CELLS = 96
const MAP_SCALE = 4
const WIDTH = 128
const SLICE_SCALE = 3
const STEPS = [2, 4, 8, 16, 32]

const idOf = (path: string) => path.replace(/^.*\//, '').replace(/\.json$/, '')

/** Reads [names]' `.nbt` files as the preview places them; one that can't be read is left out, as on a server. */
function useStructures(names: string[]): {
  structures: Record<string, TerrainStructureInput>
  key: string
} | null {
  const { backend } = useApp()
  const files = useWorkspace((s) => s.files)
  const stamps = useWorkspace((s) => s.fileStamps)
  const paths = names.map((name) => mainFileOf('structure', name))
  const key = paths
    .map((path) => `${path}@${files.includes(path) ? (stamps[path] ?? 0) : '-'}`)
    .join('|')
  const loaded = useAsyncResult(key, async () => {
    const structures: Record<string, TerrainStructureInput> = {}
    for (const [i, name] of names.entries()) {
      const path = paths[i]!
      if (!files.includes(path)) continue
      try {
        const structure = parseStructure(
          await readNbt(await readProjectBytes(backend, path, stamps[path])),
        )
        structures[name] = structureInput(structure)
      } catch {
        // Not a structure: not placed, as the server places none.
      }
    }
    return { structures, key }
  })
  if (names.length === 0) return { structures: {}, key: '' }
  return loaded?.value?.key === key ? loaded.value : null
}

const NO_SOURCES = { sources: {}, key: '' }

/**
 * The Lua terrain [id]'s script may run (its own file and the modules'), when [scripted]: an open document's text
 * as it is in the editor, else the file on disk (read again when its stamp changes).
 */
function useScriptSources(
  id: string,
  scripted: boolean,
): { sources: Record<string, string>; key: string } | null {
  const { backend } = useApp()
  const files = useWorkspace((s) => s.files)
  const stamps = useWorkspace((s) => s.fileStamps)
  const docs = useWorkspace((s) => s.docs)
  const paths = scripted ? scriptPaths(id, files) : []
  const open = (path: string) => {
    const doc = docs[path]
    return doc && !doc.deleted && !isModel(doc) ? doc.text : undefined
  }
  const key = paths
    .map((path) => {
      const text = open(path)
      return text === undefined ? `${path}@${stamps[path] ?? 0}` : `${path}#${text}`
    })
    .join('|')
  const loaded = useAsyncResult(key, async () => {
    const sources: Record<string, string> = {}
    for (const path of paths) {
      try {
        sources[path] = open(path) ?? (await backend.readText(path))
      } catch {
        // Gone meanwhile: a require of it says there's no such module, as on the server.
      }
    }
    return { sources, key }
  })
  if (!scripted) return NO_SOURCES
  return loaded?.value?.key === key ? loaded.value : null
}

/**
 * The heights the preview can draw a world of: the overworld's, and each `netherforge.json` world's that names this
 * terrain and a project dimension type, from the manifest and the dimension files as they are now (an open
 * document's edits included).
 */
function useHeightChoices(terrain: string): HeightChoice[] {
  const home = useHomeNamespace()
  const files = useWorkspace((s) => s.files)
  const docs = useWorkspace((s) => s.docs)
  const diskTexts = useWorkspace((s) => s.diskTexts)
  return useMemo(() => {
    const texts = { docs, diskTexts }
    const manifest = parseCached<ProjectManifest>(currentText(texts, MANIFEST_FILE))
    const dimensions: Record<string, DimensionTypeFile | null> = {}
    for (const id of resourceIdsOf(files, 'dimension_type')) {
      dimensions[id] = parseCached<DimensionTypeFile>(
        currentText(texts, mainFileOf('dimension_type', id)),
      )
    }
    return heightChoices(manifest, home, terrain, dimensions)
  }, [home, files, docs, diskTexts, terrain])
}

export function TerrainEditor({ path }: { path: string }) {
  const { doc, model, edit } = useModelDoc<TerrainFile>(path)
  const view = useView('terrain', path)
  const state = useViewState('terrain', path)
  useFocusRequests(path, (segments) => segments)
  const choices = useHeightChoices(idOf(path))
  const height = pickHeights(choices, state.heights)

  // What the preview shows is the file as the editor holds it, drawn by the worker: the newest request wins.
  const text = useMemo(() => (model ? JSON.stringify(model) : ''), [model])
  const named = useMemo(() => structuresNamed(model?.decorations), [model])
  const structures = useStructures(named)
  const scripts = useScriptSources(idOf(path), hasScript(text))
  const x0 = state.x - Math.floor((CELLS * state.step) / 2)
  const z0 = state.z - Math.floor((CELLS * state.step) / 2)
  const from = (state.alongX ? state.x : state.z) - WIDTH / 2
  const request = useMemo<PreviewRequest | null>(
    () =>
      text && structures && scripts
        ? {
            id: idOf(path),
            text,
            structures: structures.structures,
            structuresKey: structures.key,
            sources: scripts.sources,
            sourcesKey: scripts.key,
            seed: String(state.seed),
            minY: height.minY,
            maxY: height.maxY,
            map: { x0, z0, cells: CELLS, step: state.step },
            slice: { alongX: state.alongX, at: state.at, from, width: WIDTH },
          }
        : null,
    [
      path,
      text,
      structures,
      scripts,
      state.seed,
      height.minY,
      height.maxY,
      x0,
      z0,
      state.step,
      state.alongX,
      state.at,
      from,
    ],
  )
  const preview = useTerrainPreview(request)
  const map = preview.answer?.map ?? null
  const slice = preview.answer?.slice ?? null

  if (!doc) return null
  if (!model) return <RawDocView path={path} />

  const failed = map?.type === 'failed' ? map : slice?.type === 'failed' ? slice : null
  const scriptErrors = scriptErrorsOf(map, slice)
  return (
    <EditorScreen>
      <EditorBar title={idOf(path)}>
        <label className={styles.seed}>
          Seed
          <NumberInput
            label="Seed"
            value={state.seed}
            step={1}
            onChange={(value) => view.update({ seed: Math.round(value ?? 1) })}
          />
        </label>
        <Button
          icon="refresh"
          onClick={() => view.update({ seed: Math.floor(Math.random() * 2 ** 31) })}
        >
          Random seed
        </Button>
        {choices.length > 1 && (
          <label className={styles.seed}>
            Heights
            <select
              aria-label="Preview heights"
              value={height.key}
              onChange={(event) => view.update({ heights: event.target.value })}
            >
              {choices.map((choice) => (
                <option key={choice.key} value={choice.key}>
                  {choice.label}
                </option>
              ))}
            </select>
          </label>
        )}
        <EditAsJson path={path} />
      </EditorBar>
      <Stage className={styles.stage}>
        {failed && (
          <section className={styles.failed} role="alert" aria-label="The preview can't draw this">
            <strong>The terrain can't be drawn yet:</strong>
            <ul>
              {failed.problems.slice(0, 5).map((problem, index) => (
                <li key={index}>{problem.message}</li>
              ))}
            </ul>
          </section>
        )}
        {!failed && scriptErrors.length > 0 && <ScriptErrors errors={scriptErrors} />}
        <div
          className={styles.previews}
          data-testid="terrain-previews"
          data-drawing={preview.drawing || undefined}
          aria-busy={preview.drawing}
        >
          {!failed && map?.type === 'map' && (
            <MapPreview
              map={map}
              alongX={state.alongX}
              at={state.at}
              centre={{ x: state.x, z: state.z }}
              onMove={(x, z) => view.update({ x, z })}
              onCut={(alongX, at) => view.update({ alongX, at })}
            />
          )}
          {!failed && slice?.type === 'slice' && map?.type === 'map' && (
            <SlicePreview
              slice={slice}
              map={map}
              fluid={model.terrain?.fluid ?? TERRAIN_DEFAULTS.fluid}
              stone={model.stone?.block ?? TERRAIN_DEFAULTS.stone}
            />
          )}
        </div>
        {!map && (
          <Empty>{preview.error ?? (preview.drawing ? 'Drawing…' : 'Nothing to draw.')}</Empty>
        )}
        <Hint>
          Drawn by the same code the server runs, for the seed above. Drag the map to move it; click
          it to cut the slice there.
        </Hint>
      </Stage>
      <InspectorPanel>
        <div className={styles.inspectorTools}>
          <SelectField
            label="Cell size"
            value={String(state.step)}
            options={STEPS.map((it) => ({ value: String(it), label: `${it} blocks` }))}
            onChange={(value) => view.update({ step: Number(value) })}
          />
          <SelectField
            label="Slice along"
            value={state.alongX ? 'x' : 'z'}
            options={[
              { value: 'x', label: 'the x axis' },
              { value: 'z', label: 'the z axis' },
            ]}
            onChange={(value) => view.update({ alongX: value === 'x' })}
          />
        </div>
        <TerrainInspector path={path} model={model} edit={edit} />
      </InspectorPanel>
    </EditorScreen>
  )
}

// ---- the script -----------------------------------------------------------------------------------

/** What the script failed at in either picture, each once. */
function scriptErrorsOf(
  map: TerrainPreviewResult | null,
  slice: TerrainPreviewResult | null,
): TerrainScriptError[] {
  const seen = new Map<string, TerrainScriptError>()
  for (const result of [map, slice]) {
    if (result?.type !== 'map' && result?.type !== 'slice') continue
    for (const error of result.scriptErrors) seen.set(`${error.stage}\n${error.message}`, error)
  }
  return [...seen.values()]
}

/** The script's failures, each opening the file at its line: what failed is drawn as the file alone makes it. */
function ScriptErrors({ errors }: { errors: TerrainScriptError[] }) {
  const { workspace } = useApp()
  return (
    <section className={styles.failed} role="alert" aria-label="The script failed">
      <strong>The script failed, so the file's own result is drawn there:</strong>
      <ul>
        {errors.slice(0, 5).map((error) => (
          <li key={`${error.stage}\n${error.message}`}>
            <Button
              variant="link"
              onClick={() =>
                void workspace.getState().openFile(error.file, { line: error.line ?? undefined })
              }
            >
              {error.stage === 'load' ? 'loading it' : `its ${error.stage} stage`}
            </Button>
            : {error.message}
          </li>
        ))}
      </ul>
    </section>
  )
}

// ---- the map --------------------------------------------------------------------------------------

/** Puts RGBA pixels on a canvas. */
function useCanvas(pixels: { width: number; height: number; data: Uint8ClampedArray } | null) {
  const ref = useRef<HTMLCanvasElement>(null)
  useEffect(() => {
    const canvas = ref.current
    const context = canvas?.getContext('2d')
    if (!canvas || !context || !pixels) return
    canvas.width = pixels.width
    canvas.height = pixels.height
    context.putImageData(
      new ImageData(new Uint8ClampedArray(pixels.data), pixels.width, pixels.height),
      0,
      0,
    )
  }, [pixels])
  return ref
}

function MapPreview({
  map,
  alongX,
  at,
  centre,
  onMove,
  onCut,
}: {
  map: TerrainMap
  alongX: boolean
  at: number
  centre: { x: number; z: number }
  onMove: (x: number, z: number) => void
  onCut: (alongX: boolean, at: number) => void
}) {
  const pixels = useMemo(
    () => ({ width: map.cells, height: map.cells, data: mapPixels(map) }),
    [map],
  )
  const canvas = useCanvas(pixels)
  const [hover, setHover] = useState<{ x: number; z: number } | null>(null)
  const drag = useRef<{
    px: number
    py: number
    moved: boolean
    from: { x: number; z: number }
  } | null>(null)
  const size = map.cells * MAP_SCALE

  /** The block a pointer is over. */
  const blockOf = (event: ReactPointerEvent<HTMLElement>) => {
    const box = event.currentTarget.getBoundingClientRect()
    const cx = Math.min(
      map.cells - 1,
      Math.max(0, Math.floor(((event.clientX - box.left) / box.width) * map.cells)),
    )
    const cz = Math.min(
      map.cells - 1,
      Math.max(0, Math.floor(((event.clientY - box.top) / box.height) * map.cells)),
    )
    return { cx, cz, x: map.x0 + cx * map.step, z: map.z0 + cz * map.step }
  }
  const over = hover && {
    ...hover,
    cx: Math.floor((hover.x - map.x0) / map.step),
    cz: Math.floor((hover.z - map.z0) / map.step),
  }
  const index = over ? over.cz * map.cells + over.cx : -1
  const area = index >= 0 ? map.areas[index] : undefined
  const decorationsHere = index >= 0 ? decorationsAt(map, index) : []
  const marked = new Set(map.decorations.filter((_, i) => i % 2 === 1))
  // The slice's line, as a fraction of the map.
  const line = ((alongX ? at - map.z0 : at - map.x0) / (map.cells * map.step)) * 100

  return (
    <figure className={styles.figure}>
      <figcaption>
        <strong>From above</strong>{' '}
        <Muted>
          {map.cells * map.step} blocks across, centred on {place(centre.x, centre.z)}
        </Muted>
      </figcaption>
      <div
        className={styles.map}
        style={{ width: size, height: size }}
        role="img"
        aria-label="Map of the terrain from above"
        data-testid="terrain-map"
        onPointerDown={(event) => {
          event.currentTarget.setPointerCapture(event.pointerId)
          drag.current = { px: event.clientX, py: event.clientY, moved: false, from: centre }
        }}
        onPointerMove={(event) => {
          const here = blockOf(event)
          setHover({ x: here.x, z: here.z })
          const d = drag.current
          if (!d) return
          const dx = event.clientX - d.px
          const dy = event.clientY - d.py
          if (Math.abs(dx) + Math.abs(dy) > 3) d.moved = true
          if (d.moved)
            onMove(
              d.from.x - Math.round((dx / MAP_SCALE) * map.step),
              d.from.z - Math.round((dy / MAP_SCALE) * map.step),
            )
        }}
        onPointerUp={(event) => {
          const d = drag.current
          drag.current = null
          if (d && !d.moved) {
            const here = blockOf(event)
            onCut(alongX, alongX ? here.z : here.x)
          }
        }}
        onPointerLeave={() => setHover(null)}
      >
        <canvas ref={canvas} className={styles.canvas} />
        <span
          className={alongX ? styles.lineX : styles.lineZ}
          style={alongX ? { top: `${line}%` } : { left: `${line}%` }}
          aria-hidden="true"
        />
      </div>
      <p className={styles.readout} aria-live="polite" data-testid="terrain-map-readout">
        {over && index >= 0
          ? `${place(over.x, over.z)}: ground at ${map.heights[index]}${map.heights[index]! < map.seaLevel ? ' (under the sea)' : ''}, ${area !== undefined ? `${map.areaNames[area]} (${map.biomes[area]})` : ''}${decorationsHere.length ? `; ${decorationsHere.map((i) => map.decorationNames[i]).join(', ')}` : ''}`
          : `Sea level ${map.seaLevel}.`}
      </p>
      <ul className={styles.legend} aria-label="Biome areas">
        {map.areaNames.map((name, i) => {
          const [r, g, b] = areaColor(i)
          return (
            <li key={name}>
              <span className={styles.swatch} style={{ background: `rgb(${r} ${g} ${b})` }} />
              {name} <Muted>{map.biomes[i]}</Muted>
            </li>
          )
        })}
        <li>
          <span className={styles.swatch} style={{ background: 'rgb(48 96 184)' }} /> the sea
        </li>
      </ul>
      {map.decorationNames.length > 0 && (
        <ul className={styles.legend} aria-label="Decorations" data-testid="terrain-decorations">
          {map.decorationNames.map((name, i) => {
            if (!marked.has(i)) return null
            const [r, g, b] = decorationColor(i)
            return (
              <li key={name}>
                <span className={styles.swatch} style={{ background: `rgb(${r} ${g} ${b})` }} />
                {name}
              </li>
            )
          })}
          {!map.decorationsShown && (
            <li>
              <Muted>Decorations are marked on a closer map (a smaller cell size).</Muted>
            </li>
          )}
        </ul>
      )}
    </figure>
  )
}

// ---- the slice ------------------------------------------------------------------------------------

function SlicePreview({
  slice,
  map,
  fluid,
  stone,
}: {
  slice: TerrainSlice
  map: TerrainMap
  fluid: string
  stone: string
}) {
  const colors = useMemo(() => sliceColors(slice, fluid, stone), [slice, fluid, stone])
  const top = useMemo(() => sliceTop(slice), [slice])
  const drawn = useMemo(() => {
    const out = slicePixels(slice, colors, top, SLICE_SCALE)
    return { width: out.width, height: out.height, data: out.pixels }
  }, [slice, colors, top])
  const canvas = useCanvas(drawn)
  const [hover, setHover] = useState<{ column: number; y: number } | null>(null)
  const used = useMemo(() => {
    const seen = new Set<number>()
    for (const runs of slice.columns) for (let i = 0; i < runs.length; i += 2) seen.add(runs[i]!)
    return [...seen].filter((it) => it !== 0).sort((a, b) => a - b)
  }, [slice])
  const sea = ((top - map.seaLevel - 1) / (top - slice.minY)) * 100

  return (
    <figure className={styles.figure}>
      <figcaption>
        <strong>Through the ground</strong>{' '}
        <Muted>
          {slice.axis === 'x' ? `along x, at z ${slice.at}` : `along z, at x ${slice.at}`},{' '}
          {slice.width} blocks from {slice.from}
        </Muted>
      </figcaption>
      <div
        className={styles.slice}
        style={{ width: drawn.width, height: drawn.height }}
        role="img"
        aria-label="Slice through the ground"
        data-testid="terrain-slice"
        onPointerMove={(event) => {
          const box = event.currentTarget.getBoundingClientRect()
          const column = Math.floor(((event.clientX - box.left) / box.width) * slice.width)
          const y =
            top - 1 - Math.floor(((event.clientY - box.top) / box.height) * (top - slice.minY))
          setHover({ column, y })
        }}
        onPointerLeave={() => setHover(null)}
      >
        <canvas ref={canvas} className={styles.canvas} />
        <span className={styles.sea} style={{ top: `${sea}%` }} aria-hidden="true" />
      </div>
      <p className={styles.readout} aria-live="polite" data-testid="terrain-slice-readout">
        {hover && hover.column >= 0 && hover.column < slice.width
          ? `${slice.axis} ${slice.from + hover.column}, y ${hover.y}: ${slice.palette[blockAt(slice, hover.column, hover.y)]?.label ?? ''}`
          : `Lowest block ${slice.minY}.`}
      </p>
      <ul className={styles.legend} aria-label="Blocks">
        {used.map((index) => {
          const color = colors[index]
          const entry = slice.palette[index]!
          return (
            <li key={index}>
              <span
                className={styles.swatch}
                style={{ background: color ? `rgb(${color[0]} ${color[1]} ${color[2]})` : 'none' }}
              />
              {entry.label} {entry.custom && <Muted>(the project's)</Muted>}
            </li>
          )
        })}
      </ul>
    </figure>
  )
}
