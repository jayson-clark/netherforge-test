/**
 * "Generates in the world" on a structure's screen: `structures/<id>.json`,
 * where the game places the structure by itself (biomes, spacing, height,
 * terrain). The section is a switch: on writes a new file through format,
 * off deletes it (after asking). Each change is saved at once, since this
 * file has no tab of its own to save from. Pools (more pieces) are listed
 * but edited in the file: docs/format/worlds.md has them.
 */
import { useEffect } from 'react'
import {
  canonicalizeModel,
  GenerationStepValues,
  StructureHeightmapValues,
  TerrainAdaptationValues,
  type GenerationStep,
  type StructureGeneration,
  type StructureHeightmap,
  type TerrainAdaptation,
} from '@/core/format'
import { setKey } from '@/core/draft'
import { modelOf } from '@/core/store/documents'
import { useApp, useWorkspace } from '@/state/providers'
import { ask } from '@/ui/dialogs'
import { NumberField, Section, SelectField, TextField } from '@/ui/fields'
import { Hint } from '@/ui/text'
import { biomesText, newGeneration, poolNames, setBiomes, setCount } from './ops'

const label = (value: string) => value.replaceAll('_', ' ')

export function Generation({ id, path, exists }: { id: string; path: string; exists: boolean }) {
  const { workspace } = useApp()
  const doc = useWorkspace((s) => s.docs[path])
  const allProblems = useWorkspace((s) => s.problems)
  const problems = allProblems.filter((it) => it.file === path)
  const model = modelOf<StructureGeneration>(doc)

  // This file has no tab: load it for the screen.
  useEffect(() => {
    if (exists) void workspace.getState().loadDoc(path)
  }, [exists, path, workspace])

  const edit = (recipe: (draft: StructureGeneration) => void) => {
    workspace.getState().edit<StructureGeneration>(path, recipe)
    void workspace.getState().save(path)
  }

  const toggle = async (on: boolean) => {
    if (on) {
      const written = canonicalizeModel('structure_generation', path, newGeneration())
      if (written.text) await workspace.getState().createFile(path, written.text)
      return
    }
    const ok = await ask.confirm({
      title: `Stop generating ${id}`,
      message: `Delete ${path}? The structure stays, and scripts can still place it; it just won't appear in new chunks by itself. Git can bring the file back if it was committed.`,
      confirmLabel: 'Delete',
      danger: true,
    })
    if (ok === true) await workspace.getState().deletePath(path)
  }

  return (
    <Section title="Generates in the world" enabled={exists} onToggle={(on) => void toggle(on)}>
      {!exists && (
        <Hint>
          Turn this on to have the game place {id} by itself in new chunks, in the biomes you pick.
        </Hint>
      )}
      {exists && !model && (
        <Hint tone="error" role="status">
          {path} can't be read as a generation file
          {doc?.parseProblem ? `: ${doc.parseProblem.message}` : ''}.
        </Hint>
      )}
      {model && (
        <>
          <TextField
            label="Biomes"
            dataPath="biomes"
            value={biomesText(model)}
            placeholder="#minecraft:is_overworld"
            onChange={(text) => edit((draft) => setBiomes(draft, text))}
          />
          <Hint>Biome ids, or one #tag, separated by commas.</Hint>
          <NumberField
            label="Spacing (chunks)"
            dataPath="spacing"
            value={model.spacing}
            placeholder="32"
            step={1}
            min={1}
            onChange={(value) => edit((draft) => setCount(draft, 'spacing', value))}
          />
          <NumberField
            label="Separation (chunks)"
            dataPath="separation"
            value={model.separation}
            placeholder="8"
            step={1}
            min={0}
            onChange={(value) => edit((draft) => setCount(draft, 'separation', value))}
          />
          <SelectField<GenerationStep>
            label="Step"
            dataPath="step"
            value={model.step ?? 'surface_structures'}
            options={GenerationStepValues.map((value) => ({ value, label: label(value) }))}
            onChange={(value) =>
              edit((draft) =>
                setKey(draft, 'step', value === 'surface_structures' ? undefined : value),
              )
            }
          />
          <SelectField<TerrainAdaptation>
            label="Terrain adaptation"
            dataPath="terrainAdaptation"
            value={model.terrainAdaptation ?? 'none'}
            options={TerrainAdaptationValues.map((value) => ({ value, label: label(value) }))}
            onChange={(value) =>
              edit((draft) =>
                setKey(draft, 'terrainAdaptation', value === 'none' ? undefined : value),
              )
            }
          />
          <SelectField<StructureHeightmap>
            label="Height from"
            dataPath="heightmap"
            value={model.heightmap ?? 'world_surface_wg'}
            options={StructureHeightmapValues.map((value) => ({
              value,
              label: value === 'none' ? 'an absolute height' : label(value),
            }))}
            onChange={(value) =>
              edit((draft) =>
                setKey(draft, 'heightmap', value === 'world_surface_wg' ? undefined : value),
              )
            }
          />
          <NumberField
            label="Start height"
            dataPath="startHeight"
            value={model.startHeight}
            placeholder="0"
            step={1}
            onChange={(value) => edit((draft) => setCount(draft, 'startHeight', value))}
          />
          {poolNames(model).length > 0 && (
            <Hint aria-label="Pools">
              Pieces from pools: {poolNames(model).join(', ')}. Edit them in {path}.
            </Hint>
          )}
        </>
      )}
      {problems.map((problem, index) => (
        <Hint key={index} tone={problem.severity === 'error' ? 'error' : undefined} role="status">
          {problem.message}
        </Hint>
      ))}
      <Hint>Changing this restarts the dev server: the game reads structures as it starts.</Hint>
    </Section>
  )
}
