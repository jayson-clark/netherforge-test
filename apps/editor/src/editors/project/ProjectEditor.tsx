/**
 * Project settings: `netherforge.json` as a form. The name, namespace and
 * version, the target and format (shown, not changed here: a new target or
 * format is a different project), `managedWorlds`, the existing server worlds scripts may unload
 * and delete, `requires`, what the project's scripts need that a package must declare
 * (moderation, a database, HTTP hosts, other plugins), beside what the whole tree requires
 * (format's combined list, from the outline), `allow`, the permissions scripts may grant, and
 * `worlds`, how mobs spawn per world, and `settings`, what the server's owner may change ([SettingsSection]). Edits
 * go through the store like any model document, so undo, canonical saving
 * and hot reload are the usual ones; "Edit as JSON" is the way to anything
 * else.
 */
import { useState } from 'react'
import {
  HTTP_HOST_PATTERN,
  HTTP_HOST_RULE,
  NAMESPACE_RULE,
  PERMISSION_NODE_PATTERN,
  PERMISSION_NODE_RULE,
  PLUGIN_NAME_PATTERN,
  PLUGIN_NAME_RULE,
  SpawnCategoryValues,
  type SpawnCategory,
  WORLD_NAME_PATTERN,
  WORLD_NAME_RULE,
  type ProjectManifest,
} from '@/core/format'
import { useWorkspace } from '@/state/providers'
import { CheckField, NumberInput, Row, Section, TextField } from '@/ui/fields'
import { IconButton, Button } from '@/ui/Button'
import { Hint, Mono, Muted } from '@/ui/text'
import { useFocusRequests } from '@/editors/shared/useFocusRequests'
import { EditAsJson, useModelDoc } from '@/editors/shared/modelDoc'
import { RawDocView } from '@/editors/shared/RawDocView'
import { EditorBar, EditorScreen, Page } from '@/editors/shared/EditorLayout'
import styles from './ProjectEditor.module.css'
import { SettingsSection } from './SettingsSection'
import {
  addManagedWorld,
  addPermission,
  addRequired,
  removeManagedWorld,
  removePermission,
  removeRequired,
  removeWorldSpawn,
  setRequired,
  setWorldSpawn,
  type RequiredFlag,
  type RequiredList,
  type SpawnField,
} from './ops'

const capitalised = (rule: string) => rule.charAt(0).toUpperCase() + rule.slice(1)
const WORLD_RULE = capitalised(WORLD_NAME_RULE)
const PERMISSION_RULE = capitalised(PERMISSION_NODE_RULE)
const HOST_RULE = capitalised(HTTP_HOST_RULE)
const PLUGIN_RULE = capitalised(PLUGIN_NAME_RULE)

export function ProjectEditor({ path }: { path: string }) {
  const { doc, model, edit } = useModelDoc<ProjectManifest>(path)
  useFocusRequests(path, (segments) => segments)
  if (!doc) return null
  if (!model) return <RawDocView path={path} />
  return (
    <EditorScreen aria-label="Project settings">
      <EditorBar title="Project settings">
        <EditAsJson path={path} />
      </EditorBar>
      <Page className={styles.page}>
        <Section title="Project">
          <TextField
            label="Name"
            dataPath="name"
            value={model.name}
            onChange={(name) =>
              edit((draft) => {
                draft.name = name
              })
            }
          />
          <TextField
            label="Namespace"
            dataPath="namespace"
            value={model.namespace}
            onChange={(namespace) =>
              edit((draft) => {
                draft.namespace = namespace
              })
            }
          />
          <Hint>
            Everything the project registers or saves on a server is named in it (a stack of the
            item <Mono>ruby</Mono> is <Mono>{model.namespace}:ruby</Mono>), so stacks and recipes a
            server already has keep the old one if it changes. {capitalised(NAMESPACE_RULE)}.
          </Hint>
          <TextField
            label="Version"
            dataPath="version"
            value={model.version}
            onChange={(version) =>
              edit((draft) => {
                draft.version = version
              })
            }
          />
          <Row label="Minecraft">
            <span aria-label="Target Minecraft version">{model.minecraft}</span>
          </Row>
          <Row label="Format">
            <span>{model.formatVersion}</span>
          </Row>
        </Section>
        <ManagedWorlds
          worlds={model.managedWorlds ?? []}
          onAdd={(name) => edit((draft) => addManagedWorld(draft, name))}
          onRemove={(name) => edit((draft) => removeManagedWorld(draft, name))}
        />
        <WorldSpawning
          worlds={model.worlds}
          onSet={(world, field, category, value) =>
            edit((draft) => setWorldSpawn(draft, world, field, category, value))
          }
          onRemove={(world) => edit((draft) => removeWorldSpawn(draft, world))}
        />
        <Requirements
          requires={model.requires}
          onFlag={(key, on) => edit((draft) => setRequired(draft, key, on))}
          onAdd={(key, name) => edit((draft) => addRequired(draft, key, name))}
          onRemove={(key, name) => edit((draft) => removeRequired(draft, key, name))}
        />
        <TreeRequirements />
        <Permissions
          allow={model.allow}
          onAdd={(node) => edit((draft) => addPermission(draft, node))}
          onRemove={(node) => edit((draft) => removePermission(draft, node))}
        />
        <SettingsSection settings={model.settings} edit={edit} />
      </Page>
    </EditorScreen>
  )
}

function ManagedWorlds({
  worlds,
  onAdd,
  onRemove,
}: {
  worlds: string[]
  onAdd: (name: string) => void
  onRemove: (name: string) => void
}) {
  return (
    <Section title="Managed worlds">
      <Hint>
        Worlds already on the server that this project&apos;s scripts may unload and delete. Worlds
        its scripts create are the project&apos;s anyway; the server&apos;s main world never is.
      </Hint>
      <NameList
        label="Managed worlds"
        dataPath="managedWorlds"
        names={worlds}
        pattern={WORLD_NAME_PATTERN}
        rule={WORLD_RULE}
        listed="That world is listed already"
        inputLabel="World name"
        placeholder="A world on the server"
        addLabel="Add world"
        onAdd={onAdd}
        onRemove={onRemove}
      />
    </Section>
  )
}

/**
 * `worlds`: how the game spawns mobs in a world: the most of each spawn category there may be
 * around each player, and the ticks between spawn attempts for it. A category left blank keeps
 * the server's own setting. A world that has none of either isn't in the file, so one just added
 * is only here (`added`) until a value is set.
 */
function WorldSpawning({
  worlds,
  onSet,
  onRemove,
}: {
  worlds: ProjectManifest['worlds']
  onSet: (
    world: string,
    field: SpawnField,
    category: SpawnCategory,
    value: number | undefined,
  ) => void
  onRemove: (world: string) => void
}) {
  const [added, setAdded] = useState<string[]>([])
  const [name, setName] = useState('')
  const saved = Object.keys(worlds ?? {})
  const names = [...saved, ...added.filter((it) => !saved.includes(it))].sort()
  const trimmed = name.trim()
  const error =
    trimmed === ''
      ? null
      : !WORLD_NAME_PATTERN.test(trimmed)
        ? WORLD_RULE
        : names.includes(trimmed)
          ? 'That world is listed already'
          : null
  const add = () => {
    if (trimmed === '' || error) return
    setAdded([...added, trimmed])
    setName('')
  }
  return (
    <Section title="Mob spawning by world">
      <Hint>
        How the game spawns mobs in a world on the server, by the world&apos;s name (it needn&apos;t
        be one the project manages). The limit is the most mobs of a category around each player,
        the interval the ticks between spawn attempts; 0 stops the category spawning naturally.
        Blank keeps the server&apos;s own setting (<Mono>bukkit.yml</Mono>). Applied when the
        project loads and reloads, and whenever the world is loaded or created.
      </Hint>
      {names.length === 0 && <Muted>None.</Muted>}
      {names.map((world) => (
        <fieldset
          key={world}
          className={styles.setting}
          aria-label={`World ${world}`}
          data-path={`worlds.${world}`}
          tabIndex={-1}
        >
          <legend className={styles.settingName}>
            <Mono>{world}</Mono>
            <IconButton
              icon="trash"
              label={`Remove world ${world}`}
              onClick={() => {
                setAdded(added.filter((it) => it !== world))
                onRemove(world)
              }}
            />
          </legend>
          <table className={styles.requirements}>
            <thead>
              <tr>
                <th scope="col">Category</th>
                <th scope="col">Limit</th>
                <th scope="col">Interval (ticks)</th>
              </tr>
            </thead>
            <tbody>
              {SpawnCategoryValues.map((category) => (
                <tr key={category}>
                  <th scope="row">
                    <Mono>{category}</Mono>
                  </th>
                  {(['spawnLimits', 'spawnIntervals'] as const).map((field) => (
                    <td key={field}>
                      <NumberInput
                        label={`${category} ${field === 'spawnLimits' ? 'limit' : 'interval'}`}
                        dataPath={`worlds.${world}.${field}.${category}`}
                        value={worlds?.[world]?.[field]?.[category]}
                        placeholder="Server's"
                        step={1}
                        min={0}
                        onChange={(value) => onSet(world, field, category, value)}
                      />
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </fieldset>
      ))}
      <div className={styles.add}>
        <input
          aria-label="Spawn world name"
          placeholder="A world on the server"
          value={name}
          aria-invalid={error !== null}
          onChange={(event) => setName(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter') add()
          }}
        />
        <Button size="small" icon="plus" disabled={trimmed === '' || error !== null} onClick={add}>
          Add spawn world
        </Button>
      </div>
      {error && (
        <Hint tone="error" role="alert">
          {error}
        </Hint>
      )}
    </Section>
  )
}

/**
 * `requires`: what this project's scripts need that a package must declare. The runtime holds
 * each package to its own: a call its manifest doesn't declare is an error naming it.
 */
function Requirements({
  requires,
  onFlag,
  onAdd,
  onRemove,
}: {
  requires: ProjectManifest['requires']
  onFlag: (key: RequiredFlag, on: boolean) => void
  onAdd: (key: RequiredList, name: string) => void
  onRemove: (key: RequiredList, name: string) => void
}) {
  return (
    <Section title="What scripts need">
      <Hint>
        What this project&apos;s scripts may do only when they&apos;ve asked for it here, so whoever
        runs the project sees in one place what it needs. A script that tries without it gets an
        error saying what to add.
      </Hint>
      <CheckField
        label="Moderation"
        dataPath="requires.moderation"
        value={requires?.moderation === true}
        onChange={(on) => onFlag('moderation', on)}
      />
      <Hint>
        Lets scripts ban and unban players, change the whitelist, and change the message of the day
        and the most players allowed.
      </Hint>
      <CheckField
        label="Database"
        dataPath="requires.db"
        value={requires?.db === true}
        onChange={(on) => onFlag('db', on)}
      />
      <Hint>Lets scripts keep a database of the project&apos;s own.</Hint>
      <Hint>
        Hosts scripts may make HTTP requests to. <Mono>*.example.com</Mono> covers every host under{' '}
        <Mono>example.com</Mono>, not <Mono>example.com</Mono> itself.
      </Hint>
      <NameList
        label="HTTP hosts"
        dataPath="requires.http"
        names={requires?.http ?? []}
        pattern={HTTP_HOST_PATTERN}
        rule={HOST_RULE}
        listed="That host is listed already"
        inputLabel="Host"
        placeholder="A host, like discord.com or *.example.com"
        addLabel="Add host"
        onAdd={(name) => onAdd('http', name)}
        onRemove={(name) => onRemove('http', name)}
      />
      <Hint>Other plugins on the server that scripts may use, by name.</Hint>
      <NameList
        label="Plugins"
        dataPath="requires.plugins"
        names={requires?.plugins ?? []}
        pattern={PLUGIN_NAME_PATTERN}
        rule={PLUGIN_RULE}
        listed="That plugin is listed already"
        inputLabel="Plugin"
        placeholder="A plugin's name, like vault"
        addLabel="Add plugin"
        onAdd={(name) => onAdd('plugins', name)}
        onRemove={(name) => onRemove('plugins', name)}
      />
    </Section>
  )
}

/**
 * What the whole tree requires (the project and every package it depends on, theirs too), each
 * with who declares it: what running the project asks of a server, as format sums it.
 */
const NONE: never[] = []

function TreeRequirements() {
  const requirements = useWorkspace((s) => s.outline?.requirements ?? NONE)
  return (
    <Section title="What running it asks of a server">
      <Hint>
        Everything the project and the packages it depends on need, as the server logs it and{' '}
        <Mono>/nf requires</Mono> lists it.
      </Hint>
      {requirements.length === 0 ? (
        <Muted>Nothing beyond what every project may do.</Muted>
      ) : (
        <table className={styles.requirements} aria-label="Requirements across the packages">
          <thead>
            <tr>
              <th scope="col">Requirement</th>
              <th scope="col">Declared by</th>
            </tr>
          </thead>
          <tbody>
            {requirements.map((use) => (
              <tr key={use.requirement}>
                <td>
                  <Mono>{use.requirement}</Mono>
                </td>
                <td>{use.packages.join(', ')}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Section>
  )
}

/** `allow`: the permissions the project's scripts may grant players. */
function Permissions({
  allow,
  onAdd,
  onRemove,
}: {
  allow: ProjectManifest['allow']
  onAdd: (node: string) => void
  onRemove: (node: string) => void
}) {
  return (
    <Section title="Grantable permissions">
      <Hint>
        Permissions scripts may grant or deny players. Each covers itself and every permission under
        it: <Mono>shop</Mono> covers <Mono>shop.vip</Mono> too.
      </Hint>
      <NameList
        label="Grantable permissions"
        dataPath="allow.permissions"
        names={allow?.permissions ?? []}
        pattern={PERMISSION_NODE_PATTERN}
        rule={PERMISSION_RULE}
        listed="That permission is listed already"
        inputLabel="Permission"
        placeholder="A permission, like shop or shop.vip"
        addLabel="Add permission"
        onAdd={onAdd}
        onRemove={onRemove}
      />
    </Section>
  )
}

/**
 * Names with a remove button each and a row that adds one, checked as it's
 * typed against format's pattern. Format's length limit isn't a generated
 * constant, so a name that's too long is left to validation, whose problem
 * points at its row.
 */
function NameList({
  label,
  dataPath,
  names,
  pattern,
  rule,
  listed,
  inputLabel,
  placeholder,
  addLabel,
  onAdd,
  onRemove,
}: {
  label: string
  dataPath: string
  names: string[]
  pattern: RegExp
  rule: string
  listed: string
  inputLabel: string
  placeholder: string
  addLabel: string
  onAdd: (name: string) => void
  onRemove: (name: string) => void
}) {
  const [name, setName] = useState('')
  const trimmed = name.trim()
  const error =
    trimmed === '' ? null : !pattern.test(trimmed) ? rule : names.includes(trimmed) ? listed : null
  const add = () => {
    if (trimmed === '' || error) return
    onAdd(trimmed)
    setName('')
  }
  return (
    <>
      <ul className={styles.list} aria-label={label}>
        {names.map((it, index) => (
          <li key={it} data-path={`${dataPath}[${index}]`} tabIndex={-1}>
            <span>{it}</span>
            <IconButton icon="trash" label={`Remove ${it}`} onClick={() => onRemove(it)} />
          </li>
        ))}
        {names.length === 0 && (
          <li>
            <Muted>None.</Muted>
          </li>
        )}
      </ul>
      <div className={styles.add}>
        <input
          aria-label={inputLabel}
          placeholder={placeholder}
          value={name}
          aria-invalid={error !== null}
          onChange={(event) => setName(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter') add()
          }}
        />
        <Button size="small" icon="plus" disabled={trimmed === '' || error !== null} onClick={add}>
          {addLabel}
        </Button>
      </div>
      {error && (
        <Hint tone="error" role="alert">
          {error}
        </Hint>
      )}
    </>
  )
}
