/**
 * The pack editor: a gallery of the pack's skins, glyphs, item models,
 * tooltips and textures, and in the inspector the picked one, with a preview
 * drawn from format's `compileResourcePacks` numbers (a skin over the window it
 * fits, a glyph inside a line of text, an item model in a slot, a tooltip's
 * nine-slice frame). PNGs are imported into `textures/` and Ogg files into
 * `sounds/` as project files; every sound file is a sound event, and pack.json
 * adds events that pick among files or replace the game's. Game textures and
 * game models are the game's own (`block/stone`) it redraws, for every
 * player, each texture started from a copy of the game's out of the client
 * import. The HUD's bars, rows of icons and lines of text are gallery entries
 * too; which of the game's HUD elements it hides and which sprites it redraws
 * are the pack's own fields.
 */
import { setKey } from '@/core/draft'
import { useRef, useState, type ChangeEvent } from 'react'
import {
  GLYPH_DEFAULTS,
  HUD_LIMITS,
  HudAlignValues,
  HudElementValues,
  MENU_ROWS,
  MenuTypeValues,
  ItemModelParentValues,
  RESOURCE_PACK_SOUND_EXTENSION,
  RESOURCE_PACK_SOUND_FILE_PATTERN,
  RESOURCE_PACK_SOUND_KEY_RULE,
  RESOURCE_PACK_TEXTURE_PATH_PATTERN,
  RESOURCE_PACK_TEXTURE_PATH_RULE,
  SKIN_DEFAULTS,
  type HudAlign,
  type HudBarDef,
  type HudIconsDef,
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
import {
  CheckField,
  Check,
  Datalist,
  FieldGroup,
  NumberField,
  Row,
  Section,
  SelectField,
  TextField,
} from '@/ui/fields'
import { Button, IconButton } from '@/ui/Button'
import { cx } from '@/ui/cx'
import { Icon } from '@/ui/Icon'
import { Hint, Muted } from '@/ui/text'
import { InspectorPanel } from '@/editors/shared/docks'
import { fieldPath } from '@/editors/shared/focus'
import { EditorBar, EditorScreen } from '@/editors/shared/EditorLayout'
import { Gallery, GallerySection } from '@/editors/shared/Gallery'
import { useFocusRequests } from '@/editors/shared/useFocusRequests'
import { EditAsJson, useModelDoc } from '@/editors/shared/modelDoc'
import { RawDocView } from '@/editors/shared/RawDocView'
import {
  addEntry,
  addHudElement,
  addSoundEntry,
  DEFAULT_FOLDER,
  ENTRY_KINDS,
  entryTextures,
  freeEntryKey,
  gameTextureCopyPath,
  HUD_KINDS,
  hudKeys,
  hudKindOf,
  newHudElement,
  removeHudElement,
  removeSoundEntry,
  renameHudElement,
  renameHudIcon,
  setGameModel,
  setHudHidden,
  setHudIcon,
  setHudReplace,
  type HudKind,
  importPath,
  isOgg,
  newEntry,
  pngSize,
  removeEntry,
  renameEntry,
  setGameTexture,
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
  useResourcePackModels,
  useProjectFileUrl,
} from '@/state/useResourcePacks'
import { useGlyphMap } from '@/minecraft/text/glyphs'
import { useGameTextures, type GameTextures } from '@/minecraft/client/useGameTextures'
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
/** The `drawn` stages a text field writes: model files, comma-separated, in order; none is no `drawn`. */
const drawnFromText = (text: string) => {
  const stages = text
    .split(',')
    .map((it) => it.trim())
    .filter(Boolean)
  return stages.length > 0 ? stages : undefined
}

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
  const game = useGameTextures()
  const packModels = useResourcePackModels()
  const ws = () => workspace.getState()
  const pick = (next: Pick) => (next ? view.select(next) : view.select())

  useFocusRequests(path, (segments) => {
    const [kind, key] = segments
    // `textures` in pack.json is the game's textures it redraws, not the pack's files.
    if (kind === 'textures' && typeof key === 'string') {
      pick({ kind: 'game', key })
      return segments.slice(2)
    }
    if (kind === 'models' && typeof key === 'string') {
      pick({ kind: 'gameModel', key })
      return segments
    }
    const [, part, element] = segments
    if (kind === 'hud' && HUD_KINDS.includes(part as HudKind) && typeof element === 'string') {
      pick({ kind: 'hud', key: element })
      return segments
    }
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

  /**
   * Redraws one of the game's textures: with [texture] when given, else with a copy of the game's own picture (and
   * its animation) put in the pack to draw over.
   */
  const redraw = async (texture?: string) => {
    if (!game || Object.keys(game.sizes).length === 0) {
      ws().notify(
        'error',
        'Import Minecraft (again, if it was imported before the editor listed its textures) to pick a game texture.',
      )
      return
    }
    const names = Object.keys(game.sizes)
    const name = await ask.prompt({
      title: 'Redraw a game texture',
      label: 'Game texture (its path under textures/, without .png)',
      message:
        'Every player sees the pack’s picture instead: block/stone, item/diamond, entity/zombie/zombie…',
      confirmLabel: texture ? 'Redraw' : 'Copy and redraw',
      suggestions: names,
      validate: (value) => (value in game.sizes ? null : 'The game has no texture by that name'),
    })
    if (!name) return
    let picture = texture ?? model.textures?.[name]
    if (!picture) {
      picture = gameTextureCopyPath(name)
      if (!textures.includes(picture)) {
        const copied = await game.files(name)
        if (!copied) {
          ws().notify('error', `Couldn't read the game's ${name}`)
          return
        }
        const target = resourcePackTexturePath(pack, picture)
        if (!(await ws().writeBinary(target, copied.png))) return
        if (copied.mcmeta) await ws().writeBinary(`${target}.mcmeta`, copied.mcmeta)
      }
    }
    const chosen = picture
    edit((draft) => setGameTexture(draft, name, chosen))
    pick({ kind: 'game', key: name })
  }
  const redrawn = Object.entries(model.textures ?? {}).sort(([a], [b]) =>
    a < b ? -1 : a > b ? 1 : 0,
  )
  const remodelled = Object.entries(model.models ?? {}).sort(([a], [b]) =>
    a < b ? -1 : a > b ? 1 : 0,
  )
  const modelFiles = Object.keys(packModels[pack]?.files ?? {}).sort()

  /** Redraws one of the game's models with a model file of the pack. */
  const remodel = async () => {
    if (modelFiles.length === 0) {
      ws().notify(
        'error',
        'Put a model file in the pack’s models/ folder first: it’s what’s drawn.',
      )
      return
    }
    const name = await ask.prompt({
      title: 'Redraw a game model',
      label: 'Game model (its path under models/, without .json)',
      message: 'Every block state and item the game draws with it takes the new shape.',
      initial: 'block/',
      confirmLabel: 'Redraw',
      validate: (value) =>
        !/^[a-z0-9_]+(\/[a-z0-9_.-]+)+$/.test(value)
          ? 'A model path: block/lantern, item/stick…'
          : model.models?.[value]
            ? 'The pack redraws it already'
            : null,
    })
    if (!name) return
    edit((draft) => setGameModel(draft, name, modelFiles[0]))
    pick({ kind: 'gameModel', key: name })
  }

  const hud = hudKeys(model)
  const addHud = async (kind: HudKind) => {
    const texture = textures[0]
    if (!texture && kind !== 'texts') {
      ws().notify('error', 'Import a PNG first: a bar or a row of icons is drawn from textures.')
      return
    }
    const key = await ask.prompt({
      title: `New HUD ${HUD_LABELS[kind]}`,
      label: 'Key',
      message: `Scripts fill it as ${pack}/<key> (player:hud()).`,
      initial: '',
      confirmLabel: 'Create',
      validate: (value) =>
        !ID_PATTERN.test(value)
          ? 'Lowercase letters, digits and _'
          : hudKindOf(model, value)
            ? 'Another HUD element has that key'
            : null,
    })
    if (!key) return
    edit((draft) => addHudElement(draft, kind, key, newHudElement(kind, texture ?? '')))
    pick({ kind: 'hud', key })
  }

  const addSound = async () => {
    const key = await ask.prompt({
      title: 'New sound event',
      label: 'Key',
      message: `${pack}/<key> plays one of its files at random. A key like minecraft:block.stone.break replaces the game's event for every player.`,
      initial: '',
      confirmLabel: 'Create',
      validate: (value) =>
        !/^([a-z0-9_]+(\/[a-z0-9_]+)*|minecraft:[a-z0-9_.]+)$/.test(value)
          ? 'Lowercase letters, digits, _ and / (or minecraft:<event>)'
          : sounds.some((it) => it.key === value)
            ? 'That event exists'
            : null,
    })
    if (!key) return
    edit((draft) => addSoundEntry(draft, key, soundFiles.slice(0, 1)))
    pick({ kind: 'sounds', key })
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
          title="Game textures"
          picked={picked?.kind === 'game' ? picked.key : null}
          onPick={(key) => pick({ kind: 'game', key })}
          actions={
            <IconButton icon="plus" label="Redraw a game texture" onClick={() => void redraw()} />
          }
          cards={redrawn.map(([name, texture]) => ({
            key: name,
            label: `Game texture ${name}`,
            title: `${name} → textures/${texture}`,
            caption: name,
            picture: textures.includes(texture) ? (
              <img src={textureUrl(texture)} alt="" />
            ) : (
              <Icon name="warning" />
            ),
          }))}
        />
        <GallerySection
          title="Game models"
          picked={picked?.kind === 'gameModel' ? picked.key : null}
          onPick={(key) => pick({ kind: 'gameModel', key })}
          actions={
            <IconButton icon="plus" label="Redraw a game model" onClick={() => void remodel()} />
          }
          cards={remodelled.map(([name, file]) => ({
            key: name,
            label: `Game model ${name}`,
            title: `${name} → models/${file}`,
            caption: name,
            picture: <Icon name={modelFiles.includes(file) ? 'cube' : 'warning'} />,
          }))}
        />
        <GallerySection
          title="HUD"
          picked={picked?.kind === 'hud' ? picked.key : null}
          onPick={(key) => pick({ kind: 'hud', key })}
          actions={
            <>
              {HUD_KINDS.map((kind) => (
                <IconButton
                  key={kind}
                  icon={kind === 'bars' ? 'chart' : kind === 'icons' ? 'grid' : 'list'}
                  label={`New HUD ${HUD_LABELS[kind]}`}
                  onClick={() => void addHud(kind)}
                />
              ))}
            </>
          }
          cards={hud.map((key) => {
            const texture = hudPicture(model, key)
            return {
              key,
              label: `HUD ${HUD_LABELS[hudKindOf(model, key)!]} ${key}`,
              caption: key,
              picture:
                texture && textures.includes(texture) ? (
                  <img src={textureUrl(texture)} alt="" />
                ) : (
                  <Icon name={texture ? 'warning' : 'list'} />
                ),
            }
          })}
        />
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
          actions={
            <IconButton icon="plus" label="New sound event" onClick={() => void addSound()} />
          }
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
            onRedraw={() => void redraw(picked.key)}
            onDeleted={() => pick(null)}
          />
        )}
        {picked?.kind === 'game' && model.textures?.[picked.key] !== undefined && (
          <GameTextureInspector
            name={picked.key}
            texture={model.textures[picked.key]!}
            textures={textures}
            textureUrl={textureUrl}
            game={game}
            edit={edit}
            onRemoved={() => pick(null)}
          />
        )}
        {picked?.kind === 'gameModel' && model.models?.[picked.key] !== undefined && (
          <GameModelInspector
            name={picked.key}
            file={model.models[picked.key]!}
            modelFiles={modelFiles}
            edit={edit}
            onRemoved={() => pick(null)}
          />
        )}
        {picked?.kind === 'hud' && hudKindOf(model, picked.key) && (
          <HudInspector
            pack={pack}
            hudKey={picked.key}
            model={model}
            textures={textures}
            edit={edit}
            onPick={pick}
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
          picked.kind !== 'game' &&
          picked.kind !== 'gameModel' &&
          picked.kind !== 'hud' &&
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
        <GameHudSection model={model} textures={textures} game={game} edit={edit} />
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
  onRedraw,
  onDeleted,
}: {
  path: string
  texture: string
  url: string
  model: ResourcePackFile
  onUse: (kind: EntryKind) => void
  onRedraw: () => void
  onDeleted: () => void
}) {
  const { workspace } = useApp()
  const pack = path.split('/')[1]!
  const users = ENTRY_KINDS.flatMap((kind) =>
    Object.entries(model[kind] ?? {})
      .filter(([, def]) => entryTextures(kind, def as EntryDef).includes(texture))
      .map(([key]) => `${KIND_LABELS[kind].one} ${key}`),
  ).concat(
    Object.entries(model.textures ?? {})
      .filter(([, it]) => it === texture)
      .map(([name]) => `game texture ${name}`),
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
          <Button size="small" onClick={onRedraw}>
            game texture
          </Button>
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

function GameTextureInspector({
  name,
  texture,
  textures,
  textureUrl,
  game,
  edit,
  onRemoved,
}: {
  name: string
  texture: string
  textures: string[]
  textureUrl: (texture: string) => string
  game: GameTextures | null
  edit: (recipe: (draft: ResourcePackFile) => void) => void
  onRemoved: () => void
}) {
  const size = game?.sizes[name]
  return (
    <Section
      title={`Game texture ${name}`}
      actions={
        <IconButton
          icon="trash"
          label="Draw the game's own again"
          onClick={() => {
            edit((draft) => setGameTexture(draft, name, undefined))
            onRemoved()
          }}
        />
      }
    >
      <div className={styles.gameTexture}>
        <figure>
          <div className={cx(styles.texturePreview, styles.checker)}>
            {game && size ? (
              <img src={game.url(name)} alt={`The game's ${name}`} />
            ) : (
              <Muted>?</Muted>
            )}
          </div>
          <figcaption>The game’s{size ? ` (${size[0]}×${size[1]})` : ''}</figcaption>
        </figure>
        <figure>
          <div className={cx(styles.texturePreview, styles.checker)}>
            {textures.includes(texture) ? (
              <img src={textureUrl(texture)} alt={`The pack's ${texture}`} />
            ) : (
              <Icon name="warning" />
            )}
          </div>
          <figcaption>The pack’s</figcaption>
        </figure>
      </div>
      <SelectField
        label="Drawn with"
        dataPath={`textures.${name}`}
        value={texture}
        options={[
          ...textures.map((it) => ({ value: it, label: it })),
          ...(textures.includes(texture)
            ? []
            : [{ value: texture, label: `${texture} (missing)` }]),
        ]}
        onChange={(next) => edit((draft) => setGameTexture(draft, name, next))}
      />
      <Hint>
        Every player sees it in place of the game&apos;s. Draw it at any size of the same shape
        (32×32 for a 16×16 block is twice the detail); a screen&apos;s sheet (<code>gui/…</code>)
        and a colour map take exactly the game&apos;s size. An animation needs its{' '}
        <code>.mcmeta</code> beside the picture.
      </Hint>
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
  const at = (key: string) => fieldPath(['sounds', soundKey, key])
  const file = soundFileOf(soundKey)
  const listId = `sound-files-${pack}`
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
      <TextField
        label="Files"
        dataPath={at('files')}
        value={(def?.files ?? []).join(', ')}
        placeholder={soundKey.includes(':') ? 'step/grass_1.ogg, …' : `${file} (its own)`}
        list={listId}
        onChange={(text) => {
          const files = text
            .split(',')
            .map((it) => it.trim())
            .filter(Boolean)
          edit((draft) => setSoundValue(draft, soundKey, 'files', files.length ? files : undefined))
        }}
      />
      <Datalist id={listId} values={soundFiles} />
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
      {def && !soundFiles.includes(file) && (
        <Row label="">
          <Button
            size="small"
            icon="trash"
            onClick={() => {
              edit((draft) => removeSoundEntry(draft, soundKey))
              onDeleted()
            }}
          >
            Remove event
          </Button>
        </Row>
      )}
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
  const packModels = useResourcePackModels()
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
  // An item or block drawn as a model file has no textures of its own in pack.json: the model names them.
  const shaped =
    kind === 'items' || kind === 'blocks' ? (def as { model?: string }).model : undefined
  const modelFiles = Object.keys(packModels[pack]?.files ?? {}).sort()

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
      {(kind === 'items' || kind === 'blocks') && !block && (
        <SelectField
          label="Model file"
          dataPath={at('model')}
          value={shaped ?? ''}
          options={[
            {
              value: '',
              label: kind === 'items' ? '(none: a texture)' : '(none: a cube of textures)',
            },
            ...modelFiles.map((it) => ({ value: it, label: `models/${it}` })),
            ...(shaped && !modelFiles.includes(shaped)
              ? [{ value: shaped, label: `models/${shaped} (missing)` }]
              : []),
          ]}
          onChange={(next) =>
            editDef<Record<string, unknown>>((d) => {
              if (!next) {
                delete d.model
                return
              }
              d.model = next
              // A model's own textures draw it: the look keeps only what isn't a texture of the shape.
              for (const key of TEXTURE_FIELDS[kind]) if (key !== 'guiTexture') delete d[key]
              delete d.parent
            })
          }
        />
      )}
      {kind === 'items' && (
        <>
          <TextField
            label="Draw stages"
            dataPath={at('drawn')}
            value={((def as { drawn?: string[] }).drawn ?? []).join(', ')}
            placeholder="pull_0.json, pull_1.json"
            list="drawn-models"
            onChange={(text) => setDef('drawn', drawnFromText(text))}
          />
          <Datalist id="drawn-models" values={modelFiles} />
        </>
      )}
      {TEXTURE_FIELDS[kind]
        .filter((key) => !(block && key === 'texture'))
        .filter((key) => !(shaped && key !== 'guiTexture'))
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
          {!block && !shaped && (
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

const HUD_LABELS: Record<HudKind, string> = { bars: 'bar', icons: 'icons', texts: 'text' }

/** The picture a HUD element's card shows: a bar's full segment, a row's first icon; none for text. */
function hudPicture(model: ResourcePackFile, key: string): string | undefined {
  const hud = model.hud
  return hud?.bars?.[key]?.full ?? Object.values(hud?.icons?.[key]?.icons ?? {})[0]
}

function GameModelInspector({
  name,
  file,
  modelFiles,
  edit,
  onRemoved,
}: {
  name: string
  file: string
  modelFiles: string[]
  edit: (recipe: (draft: ResourcePackFile) => void) => void
  onRemoved: () => void
}) {
  return (
    <Section
      title={`Game model ${name}`}
      actions={
        <IconButton
          icon="trash"
          label="Draw the game's own again"
          onClick={() => {
            edit((draft) => setGameModel(draft, name, undefined))
            onRemoved()
          }}
        />
      }
    >
      <SelectField
        label="Drawn with"
        dataPath={fieldPath(['models', name])}
        value={file}
        options={[
          ...modelFiles.map((it) => ({ value: it, label: `models/${it}` })),
          ...(modelFiles.includes(file)
            ? []
            : [{ value: file, label: `models/${file} (missing)` }]),
        ]}
        onChange={(next) => edit((draft) => setGameModel(draft, name, next))}
      />
      <Hint>
        Every block state and item the game draws with {name} takes this shape, for every player.
        What collides with a block and what a player selects stay the game&apos;s.
      </Hint>
    </Section>
  )
}

/** The game's own HUD: which elements the pack hides, and which sprites it draws instead. */
function GameHudSection({
  model,
  textures,
  game,
  edit,
}: {
  model: ResourcePackFile
  textures: string[]
  game: GameTextures | null
  edit: (recipe: (draft: ResourcePackFile) => void) => void
}) {
  const hidden = model.hud?.hide ?? []
  const replaced = Object.entries(model.hud?.replace ?? {}).sort(([a], [b]) =>
    a < b ? -1 : a > b ? 1 : 0,
  )
  const sprites = game?.sprites ?? []
  const textureOptions = (current: string) => [
    ...textures.map((it) => ({ value: it, label: it })),
    ...(textures.includes(current) ? [] : [{ value: current, label: `${current} (missing)` }]),
  ]
  return (
    <Section title="The game's HUD">
      <FieldGroup header="Hidden for every player" data-path="hud.hide">
        {HudElementValues.map((element) => (
          <Check
            key={element}
            label={element}
            checked={hidden.includes(element)}
            onChange={(on) => edit((draft) => setHudHidden(draft, element, on, HudElementValues))}
          />
        ))}
      </FieldGroup>
      <FieldGroup header="Sprites drawn instead" data-path="hud.replace">
        {replaced.map(([sprite, texture]) => (
          <Row key={sprite} label={sprite}>
            <select
              aria-label={`Sprite ${sprite}`}
              data-path={fieldPath(['hud', 'replace', sprite])}
              value={texture}
              onChange={(event) =>
                edit((draft) => setHudReplace(draft, sprite, event.target.value))
              }
            >
              {textureOptions(texture).map((it) => (
                <option key={it.value} value={it.value}>
                  {it.label}
                </option>
              ))}
            </select>
            <IconButton
              icon="trash"
              label={`Draw the game's ${sprite} again`}
              onClick={() => edit((draft) => setHudReplace(draft, sprite, undefined))}
            />
          </Row>
        ))}
        <Button
          size="small"
          icon="plus"
          disabled={textures.length === 0}
          onClick={async () => {
            const sprite = await ask.prompt({
              title: 'Redraw a HUD sprite',
              label: 'Sprite (its path under the client’s textures/gui/sprites/)',
              initial: 'hud/',
              confirmLabel: 'Redraw',
              suggestions: sprites,
              validate: (value) =>
                !/^[a-z0-9_]+(\/[a-z0-9_]+)*$/.test(value)
                  ? 'A sprite name: hud/hotbar, hud/heart/full…'
                  : model.hud?.replace?.[value]
                    ? 'The pack redraws it already'
                    : null,
            })
            if (sprite) edit((draft) => setHudReplace(draft, sprite, textures[0]!))
          }}
        >
          Redraw a sprite
        </Button>
      </FieldGroup>
      <Hint>
        Hiding and redrawing apply to every player. Draw a sprite at the game&apos;s own pixel size.
      </Hint>
    </Section>
  )
}

/** One of the pack's HUD elements: a bar, a row of icons or a line of text, which scripts fill per player. */
function HudInspector({
  pack,
  hudKey,
  model,
  textures,
  edit,
  onPick,
}: {
  pack: string
  hudKey: string
  model: ResourcePackFile
  textures: string[]
  edit: (recipe: (draft: ResourcePackFile) => void) => void
  onPick: (pick: Pick) => void
}) {
  const { workspace } = useApp()
  const kind = hudKindOf(model, hudKey)!
  const def = model.hud![kind]![hudKey]!
  const at = (...rest: string[]) => fieldPath(['hud', kind, hudKey, ...rest])
  const editDef = <D,>(recipe: (draft: D) => void) =>
    edit((draft) => {
      const target = draft.hud?.[kind]?.[hudKey]
      if (target) recipe(target as D)
    })
  const set = (key: string, value: unknown) =>
    editDef<Record<string, unknown>>((d) => setKey(d, key, value))
  const whole = (value: number | undefined) => (value === undefined ? undefined : Math.round(value))
  const textureSelect = (
    label: string,
    key: string,
    value: string | undefined,
    optional: boolean,
  ) => (
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
      onChange={(next) => set(key, next || undefined)}
    />
  )
  const place = (y: boolean) => (
    <>
      <NumberField
        label="X"
        dataPath={at('x')}
        value={(def as { x: number }).x}
        step={1}
        onChange={(value) =>
          value !== undefined &&
          set('x', Math.max(-HUD_LIMITS.maxX, Math.min(HUD_LIMITS.maxX, Math.round(value))))
        }
      />
      {y && (
        <NumberField
          label="Y"
          dataPath={at('y')}
          value={(def as { y: number }).y}
          step={1}
          min={0}
          onChange={(value) =>
            value !== undefined && set('y', Math.min(HUD_LIMITS.maxY, Math.round(value)))
          }
        />
      )}
      <SelectField<HudAlign | ''>
        label="Align"
        dataPath={at('align')}
        value={(def as { align?: HudAlign }).align ?? ''}
        options={[
          { value: '', label: 'left (default)' },
          ...HudAlignValues.filter((it) => it !== 'left').map((it) => ({ value: it, label: it })),
        ]}
        onChange={(value) => set('align', value || undefined)}
      />
    </>
  )
  return (
    <Section
      title={`HUD ${HUD_LABELS[kind]} ${pack}/${hudKey}`}
      actions={
        <IconButton
          icon="trash"
          label={`Delete HUD element ${hudKey}`}
          onClick={() => {
            edit((draft) => removeHudElement(draft, hudKey))
            onPick(null)
          }}
        />
      }
    >
      <TextField
        label="Key"
        value={hudKey}
        onChange={(next) => {
          if (!ID_PATTERN.test(next) || hudKindOf(model, next)) {
            workspace
              .getState()
              .notify('error', `"${next}" isn't a free key (lowercase letters, digits and _)`)
            return
          }
          edit((draft) => renameHudElement(draft, hudKey, next))
          onPick({ kind: 'hud', key: next })
        }}
      />
      {kind === 'bars' && (
        <>
          {textureSelect('Full', 'full', (def as HudBarDef).full, false)}
          {textureSelect('Half', 'half', (def as HudBarDef).half, true)}
          {textureSelect('Empty', 'empty', (def as HudBarDef).empty, false)}
          <NumberField
            label="Segments"
            dataPath={at('segments')}
            value={(def as HudBarDef).segments}
            step={1}
            min={1}
            onChange={(value) =>
              value !== undefined &&
              set('segments', Math.min(HUD_LIMITS.maxSegments, Math.round(value)))
            }
          />
          <NumberField
            label="Gap"
            dataPath={at('gap')}
            value={(def as HudBarDef).gap}
            placeholder="0"
            step={1}
            min={0}
            onChange={(value) => set('gap', whole(value) || undefined)}
          />
          <CheckField
            label="Fills from the right"
            dataPath={at('reverse')}
            value={(def as HudBarDef).reverse === true}
            onChange={(on) => set('reverse', on || undefined)}
          />
          {place(true)}
        </>
      )}
      {kind === 'icons' && (
        <>
          <FieldGroup header="Icons" data-path={at('icons')}>
            {Object.entries((def as HudIconsDef).icons).map(([icon, texture]) => (
              <Row key={icon} label="">
                <input
                  aria-label={`Icon ${icon} key`}
                  defaultValue={icon}
                  key={icon}
                  onBlur={(event) => {
                    const next = event.target.value.trim()
                    if (next === icon) return
                    if (!ID_PATTERN.test(next) || (def as HudIconsDef).icons[next]) {
                      event.target.value = icon
                      return
                    }
                    edit((draft) => renameHudIcon(draft, hudKey, icon, next))
                  }}
                />
                <select
                  aria-label={`Icon ${icon}`}
                  data-path={at('icons', icon)}
                  value={texture}
                  onChange={(event) =>
                    edit((draft) => setHudIcon(draft, hudKey, icon, event.target.value))
                  }
                >
                  {[...textures, ...(textures.includes(texture) ? [] : [texture])].map((it) => (
                    <option key={it} value={it}>
                      {it}
                    </option>
                  ))}
                </select>
                <IconButton
                  icon="trash"
                  label={`Remove icon ${icon}`}
                  disabled={Object.keys((def as HudIconsDef).icons).length <= 1}
                  onClick={() => edit((draft) => setHudIcon(draft, hudKey, icon, undefined))}
                />
              </Row>
            ))}
            <Button
              size="small"
              icon="plus"
              disabled={textures.length === 0}
              onClick={async () => {
                const icon = await ask.prompt({
                  title: 'New icon',
                  label: 'Key (what scripts show it by)',
                  initial: '',
                  confirmLabel: 'Add',
                  validate: (value) =>
                    !ID_PATTERN.test(value)
                      ? 'Lowercase letters, digits and _'
                      : (def as HudIconsDef).icons[value]
                        ? 'That icon exists'
                        : null,
                })
                if (icon) edit((draft) => setHudIcon(draft, hudKey, icon, textures[0]!))
              }}
            >
              Add icon
            </Button>
          </FieldGroup>
          <NumberField
            label="Gap"
            dataPath={at('gap')}
            value={(def as HudIconsDef).gap}
            placeholder={String(HUD_LIMITS.iconGap)}
            step={1}
            min={0}
            onChange={(value) => set('gap', whole(value))}
          />
          {place(true)}
        </>
      )}
      {kind === 'texts' && place(false)}
      <Hint>
        GUI pixels: x from the middle of the screen ({-HUD_LIMITS.maxX} to {HUD_LIMITS.maxX}), y up
        from the bottom (0 to {HUD_LIMITS.maxY}). The hotbar&apos;s top is at 22, hearts at 30.
        {kind === 'texts' && ' A line of text is drawn on the action bar’s own line.'}
      </Hint>
    </Section>
  )
}
