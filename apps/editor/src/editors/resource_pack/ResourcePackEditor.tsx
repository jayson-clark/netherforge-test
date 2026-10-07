/**
 * The pack editor: a gallery of the pack's skins, glyphs, item models,
 * tooltips and textures, and in the inspector the picked one, with a preview
 * drawn from format's `compileResourcePacks` numbers (a skin over the window it
 * fits, a glyph inside a line of text, an item model in a slot, a tooltip's
 * nine-slice frame). PNGs are imported into `textures/` and Ogg files into
 * `sounds/` as project files; every sound file is a sound event.
 */
import { setKey } from '@/core/draft'
import { useRef, useState, type ChangeEvent } from 'react'
import {
  GLYPH_DEFAULTS,
  MENU_ROWS,
  MenuTypeValues,
  ItemModelParentValues,
  RESOURCE_PACK_SOUND_EXTENSION,
  RESOURCE_PACK_SOUND_FILE_PATTERN,
  RESOURCE_PACK_SOUND_KEY_RULE,
  RESOURCE_PACK_TEXTURE_PATH_PATTERN,
  RESOURCE_PACK_TEXTURE_PATH_RULE,
  SKIN_DEFAULTS,
  type ResourcePackFile,
  type SkinDef,
  type TooltipDef,
} from '@/core/format'
import { ItemIcon } from '@/minecraft/item/ItemIcon'
import { WindowFrame, useSkin } from '@/minecraft/window/WindowFrame'
import { ID_PATTERN, resourcePackSoundPath, resourcePackTexturePath } from '@/core/paths'
import { useApp, useWorkspace } from '@/state/providers'
import { usePrimary, useView } from '@/editors/views'
import { MiniText, TextPreview } from '@/minecraft/text/MiniText'
import { ask } from '@/ui/dialogs'
import { CheckField, NumberField, Row, Section, SelectField, TextField } from '@/ui/fields'
import { Button, IconButton } from '@/ui/Button'
import { cx } from '@/ui/cx'
import { Icon } from '@/ui/Icon'
import { Hint, Muted } from '@/ui/text'
import { InspectorPanel } from '@/editors/shared/docks'
import { EditorBar, EditorScreen } from '@/editors/shared/EditorLayout'
import { Gallery, GallerySection } from '@/editors/shared/Gallery'
import { useFocusRequests } from '@/editors/shared/useFocusRequests'
import { EditAsJson, useModelDoc } from '@/editors/shared/modelDoc'
import { RawDocView } from '@/editors/shared/RawDocView'
import {
  addEntry,
  DEFAULT_FOLDER,
  ENTRY_KINDS,
  entryTextures,
  freeEntryKey,
  importPath,
  isOgg,
  newEntry,
  pngSize,
  removeEntry,
  renameEntry,
  setSoundValue,
  soundEvents,
  soundFilesOf,
  soundImportPath,
  soundFileOf,
  soundKeyOf,
  TEXTURE_FIELDS,
  texturesOf,
  unusedTextures,
  type EntryDef,
  type EntryKind,
  type ResourcePackPick,
} from './ops'
import {
  useCompiledResourcePacks,
  useResourcePackFiles,
  useProjectFileUrl,
} from '@/state/useResourcePacks'
import { useGlyphMap } from '@/minecraft/text/glyphs'
import styles from './ResourcePackEditor.module.css'

const KIND_LABELS: Record<EntryKind, { title: string; one: string }> = {
  skins: { title: 'Skins', one: 'skin' },
  glyphs: { title: 'Glyphs', one: 'glyph' },
  items: { title: 'Item models', one: 'item model' },
  tooltips: { title: 'Tooltips', one: 'tooltip' },
  equipment: { title: 'Equipment', one: 'equipment look' },
  blocks: { title: 'Blocks', one: 'block look' },
}
/** A texture field's label: `humanoidLeggings` → `Humanoid leggings`. */
const textureLabel = (key: string) =>
  key[0]!.toUpperCase() + key.slice(1).replace(/[A-Z]/g, (it) => ` ${it.toLowerCase()}`)
const capitalized = (text: string) => text[0]!.toUpperCase() + text.slice(1)

type Pick = ResourcePackPick | null

export function ResourcePackEditor({ path }: { path: string }) {
  const { workspace } = useApp()
  const { doc, model, edit } = useModelDoc<ResourcePackFile>(path)
  const files = useWorkspace((s) => s.files)
  const picked = usePrimary('resource_pack', path)
  const view = useView('resource_pack', path)
  const pack = path.split('/')[1]!
  const textures = texturesOf(files, pack)
  const soundFiles = soundFilesOf(files, pack)
  const url = useProjectFileUrl()
  const input = useRef<HTMLInputElement>(null)
  const soundInput = useRef<HTMLInputElement>(null)
  const [importFolder, setImportFolder] = useState(DEFAULT_FOLDER.skins)
  const ws = () => workspace.getState()
  const pick = (next: Pick) => (next ? view.select(next) : view.select())

  useFocusRequests(path, (segments) => {
    const [kind, key] = segments
    if ((ENTRY_KINDS.includes(kind as EntryKind) || kind === 'sounds') && typeof key === 'string') {
      pick({ kind: kind as EntryKind | 'sounds', key })
      return segments.slice(2)
    }
    return segments
  })

  if (!doc) return null
  if (!model) return <RawDocView path={path} />

  const textureUrl = (texture: string) => url(resourcePackTexturePath(pack, texture))

  const importFiles = async (event: ChangeEvent<HTMLInputElement>) => {
    const chosen = [...(event.target.files ?? [])]
    event.target.value = ''
    let last: string | null = null
    for (const file of chosen) {
      const bytes = new Uint8Array(await file.arrayBuffer())
      if (!pngSize(bytes)) {
        ws().notify('error', `${file.name} isn't a PNG`)
        continue
      }
      const target = await ask.prompt({
        title: `Import ${file.name}`,
        label: `Save as (under resource_packs/${pack}/textures/)`,
        initial: importPath(file.name, importFolder),
        confirmLabel: 'Import',
        validate: (value) =>
          RESOURCE_PACK_TEXTURE_PATH_PATTERN.test(value)
            ? null
            : capitalized(RESOURCE_PACK_TEXTURE_PATH_RULE),
      })
      if (!target) continue
      if (textures.includes(target)) {
        const ok = await ask.confirm({
          title: 'Replace texture',
          message: `${target} exists. Replace it?`,
          confirmLabel: 'Replace',
          danger: true,
        })
        if (ok !== true) continue
      }
      if (await ws().writeBinary(resourcePackTexturePath(pack, target), bytes)) last = target
    }
    if (last) pick({ kind: 'textures', key: last })
  }

  const importSounds = async (event: ChangeEvent<HTMLInputElement>) => {
    const chosen = [...(event.target.files ?? [])]
    event.target.value = ''
    let last: string | null = null
    for (const file of chosen) {
      const bytes = new Uint8Array(await file.arrayBuffer())
      if (!isOgg(bytes)) {
        ws().notify('error', `${file.name} isn't an Ogg file: Minecraft only plays Ogg Vorbis`)
        continue
      }
      const target = await ask.prompt({
        title: `Import ${file.name}`,
        label: `Save as (under resource_packs/${pack}/sounds/)`,
        message: `It plays as ${pack}/<path without ${RESOURCE_PACK_SOUND_EXTENSION}>.`,
        initial: soundImportPath(file.name, ''),
        confirmLabel: 'Import',
        validate: (value) =>
          RESOURCE_PACK_SOUND_FILE_PATTERN.test(value)
            ? null
            : `A ${RESOURCE_PACK_SOUND_EXTENSION} path: ${RESOURCE_PACK_SOUND_KEY_RULE}`,
      })
      if (!target) continue
      if (soundFiles.includes(target)) {
        const ok = await ask.confirm({
          title: 'Replace sound',
          message: `${target} exists. Replace it?`,
          confirmLabel: 'Replace',
          danger: true,
        })
        if (ok !== true) continue
      }
      if (await ws().writeBinary(resourcePackSoundPath(pack, target), bytes)) last = target
    }
    if (last) pick({ kind: 'sounds', key: soundKeyOf(last)! })
  }

  const sounds = soundEvents(model, soundFiles)

  const add = async (kind: EntryKind, texture?: string) => {
    const from = texture ?? unusedTextures(model, textures)[0] ?? textures[0]
    if (!from) {
      ws().notify('error', 'Import a PNG first: every entry is drawn from a texture.')
      return
    }
    const key = await ask.prompt({
      title: `New ${KIND_LABELS[kind].one}`,
      label: 'Key',
      message: `Others refer to it as ${pack}/<key>.`,
      initial: freeEntryKey(model, kind, from.split('/').pop() ?? from),
      confirmLabel: 'Create',
      validate: (value) =>
        !ID_PATTERN.test(value)
          ? 'Lowercase letters, digits and _'
          : model[kind]?.[value]
            ? 'That key is taken'
            : null,
    })
    if (!key) return
    edit((draft) => addEntry(draft, kind, key, newEntry(kind, from)))
    pick({ kind, key })
  }

  return (
    <EditorScreen>
      <EditorBar
        title={
          <>
            {model.name ?? pack} <Muted>({pack}/…)</Muted>
          </>
        }
      >
        <label className={styles.into}>
          Into
          <select
            aria-label="Import into folder"
            value={importFolder}
            onChange={(event) => setImportFolder(event.target.value)}
          >
            {[...new Set([...Object.values(DEFAULT_FOLDER), ''])].map((it) => (
              <option key={it} value={it}>
                textures/{it}
              </option>
            ))}
          </select>
        </label>
        <Button size="small" icon="plus" onClick={() => input.current?.click()}>
          Import PNG…
        </Button>
        <input
          ref={input}
          type="file"
          accept="image/png"
          multiple
          hidden
          aria-label="PNG files to import"
          onChange={(event) => void importFiles(event)}
        />
        <Button size="small" icon="plus" onClick={() => soundInput.current?.click()}>
          Import OGG…
        </Button>
        <input
          ref={soundInput}
          type="file"
          accept={`${RESOURCE_PACK_SOUND_EXTENSION},audio/ogg`}
          multiple
          hidden
          aria-label="Ogg files to import"
          onChange={(event) => void importSounds(event)}
        />
        <EditAsJson path={path} />
      </EditorBar>
      <Gallery>
        {ENTRY_KINDS.map((kind) => {
          const entries = Object.entries(model[kind] ?? {}) as [string, EntryDef][]
          return (
            <GallerySection
              key={kind}
              title={KIND_LABELS[kind].title}
              picked={picked?.kind === kind ? picked.key : null}
              onPick={(key) => pick({ kind, key })}
              actions={
                <IconButton
                  icon="plus"
                  label={`New ${KIND_LABELS[kind].one}`}
                  onClick={() => void add(kind)}
                />
              }
              cards={entries.map(([key, def]) => {
                const texture = entryTextures(kind, def)[0]
                return {
                  key,
                  label: `${KIND_LABELS[kind].one} ${key}`,
                  caption: key,
                  picture:
                    texture && textures.includes(texture) ? (
                      <img src={textureUrl(texture)} alt="" />
                    ) : (
                      <Icon name="warning" />
                    ),
                }
              })}
            />
          )
        })}
        <GallerySection
          title="Textures"
          picked={picked?.kind === 'textures' ? picked.key : null}
          onPick={(key) => pick({ kind: 'textures', key })}
          cards={textures.map((texture) => ({
            key: texture,
            label: `Texture ${texture}`,
            title: texture,
            caption: (
              <>
                {texture}
                {unusedTextures(model, [texture]).length > 0 && <Muted> (unused)</Muted>}
              </>
            ),
            picture: <img src={textureUrl(texture)} alt="" />,
          }))}
        />
        <GallerySection
          title="Sounds"
          picked={picked?.kind === 'sounds' ? picked.key : null}
          onPick={(key) => pick({ kind: 'sounds', key })}
          cards={sounds.map(({ key, files: plays }) => ({
            key,
            label: `Sound ${key}`,
            title: `${pack}/${key}`,
            caption: key,
            picture: (
              <Icon name={plays.some((it) => !soundFiles.includes(it)) ? 'warning' : 'sound'} />
            ),
          }))}
        />
      </Gallery>
      <InspectorPanel>
        {picked?.kind === 'textures' && textures.includes(picked.key) && (
          <TextureInspector
            path={path}
            texture={picked.key}
            url={textureUrl(picked.key)}
            model={model}
            onUse={(kind) => void add(kind, picked.key)}
            onDeleted={() => pick(null)}
          />
        )}
        {picked?.kind === 'sounds' && sounds.some((it) => it.key === picked.key) && (
          <SoundInspector
            pack={pack}
            soundKey={picked.key}
            model={model}
            plays={sounds.find((it) => it.key === picked.key)!.files}
            soundFiles={soundFiles}
            edit={edit}
            onDeleted={() => pick(null)}
          />
        )}
        {picked &&
          picked.kind !== 'textures' &&
          picked.kind !== 'sounds' &&
          model[picked.kind]?.[picked.key] && (
            <EntryInspector
              pack={pack}
              kind={picked.kind}
              entryKey={picked.key}
              def={model[picked.kind]![picked.key]!}
              model={model}
              textures={textures}
              edit={edit}
              onPick={pick}
            />
          )}
        <Section title="Resource pack">
          <TextField
            label="Name"
            dataPath="name"
            value={model.name}
            placeholder="(for people)"
            onChange={(value) => edit((draft) => setKey(draft, 'name', value))}
          />
          <TextField
            label="Description"
            dataPath="description"
            value={model.description}
            placeholder="Shown in the client's pack list"
            onChange={(value) => edit((draft) => setKey(draft, 'description', value))}
          />
          <Hint>
            Menus, items and text refer to what&apos;s in it as <code>{pack}/&lt;key&gt;</code>, the
            pack&apos;s folder name and the key. Every <code>.ogg</code> under <code>sounds/</code>{' '}
            is the sound <code>{pack}/&lt;its path&gt;</code>.
          </Hint>
        </Section>
      </InspectorPanel>
    </EditorScreen>
  )
}

function TextureInspector({
  path,
  texture,
  url,
  model,
  onUse,
  onDeleted,
}: {
  path: string
  texture: string
  url: string
  model: ResourcePackFile
  onUse: (kind: EntryKind) => void
  onDeleted: () => void
}) {
  const { workspace } = useApp()
  const pack = path.split('/')[1]!
  const users = ENTRY_KINDS.flatMap((kind) =>
    Object.entries(model[kind] ?? {})
      .filter(([, def]) => entryTextures(kind, def as EntryDef).includes(texture))
      .map(([key]) => `${KIND_LABELS[kind].one} ${key}`),
  )
  return (
    <Section title={texture}>
      <div className={cx(styles.texturePreview, styles.checker)}>
        <img src={url} alt={texture} />
      </div>
      <Row label="Used by">
        <span>{users.length ? users.join(', ') : 'nothing yet'}</span>
      </Row>
      <Row label="Use as">
        <div className={styles.uses}>
          {ENTRY_KINDS.map((kind) => (
            <Button key={kind} size="small" onClick={() => onUse(kind)}>
              {KIND_LABELS[kind].one}
            </Button>
          ))}
        </div>
      </Row>
      <Row label="">
        <Button
          size="small"
          icon="trash"
          onClick={async () => {
            const ok = await ask.confirm({
              title: 'Delete texture',
              message: `Delete resource_packs/${pack}/textures/${texture}?${users.length ? ` ${users.join(', ')} will point at nothing.` : ''}`,
              confirmLabel: 'Delete',
              danger: true,
            })
            if (ok !== true) return
            await workspace.getState().deletePath(resourcePackTexturePath(pack, texture))
            onDeleted()
          }}
        >
          Delete file
        </Button>
      </Row>
    </Section>
  )
}

function SoundInspector({
  pack,
  soundKey,
  model,
  plays,
  soundFiles,
  edit,
  onDeleted,
}: {
  pack: string
  soundKey: string
  model: ResourcePackFile
  plays: string[]
  soundFiles: string[]
  edit: (recipe: (draft: ResourcePackFile) => void) => void
  onDeleted: () => void
}) {
  const { workspace } = useApp()
  const url = useProjectFileUrl()
  const def = model.sounds?.[soundKey]
  const at = (key: string) => `sounds.${soundKey}.${key}`
  const file = soundFileOf(soundKey)
  return (
    <Section title={`Sound ${pack}/${soundKey}`}>
      <Row label="Plays">
        <div className={styles.soundFiles}>
          {plays.map((it) =>
            soundFiles.includes(it) ? (
              <div key={it} className={styles.soundFile}>
                <span>{it}</span>
                <audio
                  controls
                  preload="none"
                  src={url(resourcePackSoundPath(pack, it))}
                  aria-label={`Play ${it}`}
                />
              </div>
            ) : (
              <Muted key={it}>{it} (missing)</Muted>
            ),
          )}
          {plays.length > 1 && <Muted>One at random each time.</Muted>}
        </div>
      </Row>
      <NumberField
        label="Volume"
        dataPath={at('volume')}
        value={def?.volume}
        placeholder="1"
        step={0.1}
        min={0}
        onChange={(value) => edit((draft) => setSoundValue(draft, soundKey, 'volume', value))}
      />
      <NumberField
        label="Pitch"
        dataPath={at('pitch')}
        value={def?.pitch}
        placeholder="1"
        step={0.1}
        min={0}
        onChange={(value) => edit((draft) => setSoundValue(draft, soundKey, 'pitch', value))}
      />
      <CheckField
        label="Stream"
        dataPath={at('stream')}
        value={def?.stream ?? false}
        onChange={(value) =>
          edit((draft) => setSoundValue(draft, soundKey, 'stream', value || undefined))
        }
      />
      <TextField
        label="Subtitle"
        dataPath={at('subtitle')}
        value={def?.subtitle}
        placeholder="(none)"
        onChange={(value) => edit((draft) => setSoundValue(draft, soundKey, 'subtitle', value))}
      />
      {soundFiles.includes(file) && (
        <Row label="">
          <Button
            size="small"
            icon="trash"
            onClick={async () => {
              const ok = await ask.confirm({
                title: 'Delete sound',
                message: `Delete resource_packs/${pack}/sounds/${file}? Scripts playing ${pack}/${soundKey} will play nothing.`,
                confirmLabel: 'Delete',
                danger: true,
              })
              if (ok !== true) return
              await workspace.getState().deletePath(resourcePackSoundPath(pack, file))
              onDeleted()
            }}
          >
            Delete file
          </Button>
        </Row>
      )}
    </Section>
  )
}

function EntryInspector({
  pack,
  kind,
  entryKey,
  def,
  model,
  textures,
  edit,
  onPick,
}: {
  pack: string
  kind: EntryKind
  entryKey: string
  def: EntryDef
  model: ResourcePackFile
  textures: string[]
  edit: (recipe: (draft: ResourcePackFile) => void) => void
  onPick: (pick: Pick) => void
}) {
  const { workspace } = useApp()
  const packs = useResourcePackFiles()
  const at = (key: string) => `${kind}.${entryKey}.${key}`
  const editDef = <D extends EntryDef>(recipe: (draft: D) => void) =>
    edit((draft) => {
      const target = draft[kind]?.[entryKey]
      if (target) recipe(target as D)
    })
  const setDef = (key: string, value: unknown) =>
    editDef((d) => setKey(d as Record<string, unknown>, key, value))
  const textureField = (key: string, label: string, optional: boolean) => {
    const value = (def as Record<string, unknown>)[key] as string | undefined
    return (
      <SelectField
        key={key}
        label={label}
        dataPath={at(key)}
        value={value ?? ''}
        options={[
          ...(optional || !value ? [{ value: '', label: optional ? '(none)' : '(choose)' }] : []),
          ...textures.map((it) => ({ value: it, label: it })),
          ...(value && !textures.includes(value) ? [{ value, label: `${value} (missing)` }] : []),
        ]}
        onChange={(next) => setDef(key, next || undefined)}
      />
    )
  }
  const one = KIND_LABELS[kind].one
  // An item drawn as a pack block has no texture of its own, and is held as a block.
  const block = kind === 'items' ? (def as { block?: string }).block : undefined
  const blockRefs = Object.entries(packs)
    .flatMap(([key, file]) => Object.keys(file.blocks ?? {}).map((entry) => `${key}/${entry}`))
    .sort()

  return (
    <Section
      title={`${one[0]!.toUpperCase()}${one.slice(1)} ${pack}/${entryKey}`}
      actions={
        <IconButton
          icon="trash"
          label={`Delete ${one} ${entryKey}`}
          onClick={() => {
            edit((draft) => removeEntry(draft, kind, entryKey))
            onPick(null)
          }}
        />
      }
    >
      <TextField
        label="Key"
        value={entryKey}
        onChange={(next) => {
          if (!ID_PATTERN.test(next) || model[kind]?.[next]) {
            workspace
              .getState()
              .notify('error', `"${next}" isn't a free key (lowercase letters, digits and _)`)
            return
          }
          edit((draft) => renameEntry(draft, kind, entryKey, next))
          onPick({ kind, key: next })
        }}
      />
      {kind === 'items' && (
        <SelectField
          label="Drawn as block"
          dataPath={at('block')}
          value={block ?? ''}
          options={[
            { value: '', label: '(none: a texture)' },
            ...blockRefs.map((it) => ({ value: it, label: it })),
            ...(block && !blockRefs.includes(block)
              ? [{ value: block, label: `${block} (missing)` }]
              : []),
          ]}
          onChange={(next) =>
            editDef<{ block?: string; texture?: string; parent?: string }>((d) => {
              if (next) {
                d.block = next
                delete d.texture
                delete d.parent
              } else delete d.block
            })
          }
        />
      )}
      {TEXTURE_FIELDS[kind]
        .filter((key) => !(block && key === 'texture'))
        .map((key) =>
          textureField(
            key,
            key === 'guiTexture' ? 'GUI texture' : textureLabel(key),
            kind === 'tooltips' ||
              kind === 'equipment' ||
              kind === 'blocks' ||
              key === 'guiTexture',
          ),
        )}
      {(kind === 'skins' || kind === 'glyphs') && (
        <>
          <NumberField
            label="Height"
            dataPath={at('height')}
            value={(def as { height?: number }).height}
            placeholder={String(kind === 'skins' ? SKIN_DEFAULTS.height : GLYPH_DEFAULTS.height)}
            step={1}
            min={1}
            onChange={(value) =>
              setDef('height', value === undefined ? undefined : Math.round(value))
            }
          />
          <NumberField
            label="Ascent"
            dataPath={at('ascent')}
            value={(def as { ascent?: number }).ascent}
            placeholder={String(kind === 'skins' ? SKIN_DEFAULTS.ascent : GLYPH_DEFAULTS.ascent)}
            step={1}
            onChange={(value) =>
              setDef('ascent', value === undefined ? undefined : Math.round(value))
            }
          />
        </>
      )}
      {kind === 'skins' && (
        <SkinFields pack={pack} entryKey={entryKey} def={def as SkinDef} at={at} setDef={setDef} />
      )}
      {kind === 'glyphs' && <GlyphPreview pack={pack} entryKey={entryKey} />}
      {kind === 'items' && (
        <>
          {!block && (
            <SelectField
              label="Held like"
              dataPath={at('parent')}
              value={(def as { parent?: string }).parent ?? ''}
              options={[
                { value: '', label: 'Default (a flat sprite)' },
                ...ItemModelParentValues.map((it) => ({ value: it, label: it })),
              ]}
              onChange={(value) => setDef('parent', value || undefined)}
            />
          )}
          <Row label="In a slot">
            <span className={styles.slotPreview}>
              <ItemIcon item={{ kind: '', itemModel: `${pack}/${entryKey}` }} size={64} />
            </span>
          </Row>
        </>
      )}
      {kind === 'tooltips' && <TooltipPreview pack={pack} def={def as TooltipDef} />}
      {kind === 'blocks' && (
        <Hint>
          The look of a block whose model is {pack}/{entryKey}: one texture for every face, or a
          top, a bottom and the sides. A face takes its own texture, then Side (for the four sides),
          then Texture.
        </Hint>
      )}
      {kind === 'equipment' && (
        <Hint>
          Worn by an item whose equipment is {pack}/{entryKey}. Give the layers its slot draws:
          humanoid for a helmet, chestplate or boots, humanoid leggings for leggings.
        </Hint>
      )}
    </Section>
  )
}

function SkinFields({
  pack,
  entryKey,
  def,
  at,
  setDef,
}: {
  pack: string
  entryKey: string
  def: SkinDef
  at: (key: string) => string
  setDef: (key: string, value: unknown) => void
}) {
  const skin = useSkin(`${pack}/${entryKey}`)
  const type = def.type ?? 'chest'
  return (
    <>
      <NumberField
        label="Offset"
        dataPath={at('offset')}
        value={def.offset}
        placeholder={String(SKIN_DEFAULTS.offset)}
        step={1}
        onChange={(value) => setDef('offset', value === undefined ? undefined : Math.round(value))}
      />
      <SelectField
        label="Drawn for"
        dataPath={at('type')}
        value={def.type ?? ''}
        options={[
          { value: '', label: '(any; preview a chest)' },
          ...MenuTypeValues.map((it) => ({ value: it, label: it })),
        ]}
        onChange={(value) => setDef('type', value || undefined)}
      />
      {type === 'chest' && (
        <SelectField
          label="Rows"
          dataPath={at('rows')}
          value={def.rows === undefined ? '' : String(def.rows)}
          options={[
            { value: '', label: `(${MENU_ROWS.default})` },
            ...Array.from({ length: MENU_ROWS.max }, (_, i) => ({
              value: String(i + 1),
              label: String(i + 1),
            })),
          ]}
          onChange={(value) => setDef('rows', value ? Number(value) : undefined)}
        />
      )}
      <div className={styles.skinPreview} aria-label="Skin preview">
        <WindowFrame
          type={type}
          rows={def.rows}
          title={undefined}
          skin={skin}
          scale={1.75}
          outline
          label="Skin over the window"
        />
        <Hint>
          Where the server draws it: the title starts at 8, 6; the prefix moves{' '}
          {skin?.skin.offset ?? SKIN_DEFAULTS.offset} px; the picture hangs{' '}
          {skin?.skin.ascent ?? SKIN_DEFAULTS.ascent} px above the title&apos;s baseline; then the
          prefix moves back
          {skin?.skin.advance == null ? ' (once the picture is measured)' : ''}, so the title&apos;s
          words are drawn over the art.
        </Hint>
      </div>
    </>
  )
}

function GlyphPreview({ pack, entryKey }: { pack: string; entryKey: string }) {
  const compiled = useCompiledResourcePacks()
  const glyphs = useGlyphMap()
  const glyph = compiled.resourcePacks[pack]?.glyphs[entryKey]
  if (!glyph) return null
  const tag = `<glyph:${pack}/${entryKey}>`
  return (
    <Row label={`In text: ${tag}`}>
      <span aria-label="Glyph preview">
        <TextPreview dark>
          <MiniText
            text={`<gray>Costs <yellow>25 ${tag}</yellow> each`}
            glyphs={glyphs}
            scale={2}
            base={{ color: '#ffffff' }}
          />
        </TextPreview>
      </span>
    </Row>
  )
}

function TooltipPreview({ pack, def }: { pack: string; def: TooltipDef }) {
  const url = useProjectFileUrl()
  const slice = (texture: string | undefined) =>
    texture
      ? `url("${url(resourcePackTexturePath(pack, texture))}") 9 fill / 18px stretch`
      : undefined
  return (
    <Row label="Preview">
      <span className={styles.tooltipPreview} aria-label="Tooltip preview">
        <span className={styles.tooltipLayer} style={{ borderImage: slice(def.background) }} />
        <span className={styles.tooltipLayer} style={{ borderImage: slice(def.frame) }} />
        <span className={styles.tooltipText}>
          <MiniText text="<aqua>Ruby" scale={2} />
          <MiniText text="<gray>50 coins" scale={2} />
        </span>
      </span>
    </Row>
  )
}
