/**
 * Display strings → drawable geometry, cached per asset set and id: the
 * viewport re-renders on every edit, and re-baking an unchanged block each
 * time would be waste.
 */
import * as THREE from 'three'
import type { ClientAssets } from './assets'
import { buildElements, buildSprite, placementMatrix, type BuiltGroup } from './geometry'
import {
  blockPlacements,
  isSpriteModel,
  itemModelRef,
  resolveModel,
  splitId,
  spriteLayers,
  type DisplayEntry,
} from './model'

/**
 * Tint for faces with a `tintindex` (grass, leaves, water). The real colour
 * depends on the biome; the editor pretends to be in plains. These are
 * colours, not game facts about any particular block.
 */
const TINTS = {
  grass: new THREE.Color(0x91bd59),
  foliage: new THREE.Color(0x77ab2f),
  water: new THREE.Color(0x3f76e4),
}

export function tintFor(id: string): THREE.Color {
  const { path } = splitId(id)
  if (path.includes('water')) return TINTS.water
  if (path.includes('leaves') || path.includes('vine')) return TINTS.foliage
  return TINTS.grass
}

const blocks = new WeakMap<ClientAssets, Map<string, Promise<BuiltGroup[]>>>()
const items = new WeakMap<ClientAssets, Map<string, Promise<ItemGeometry | null>>>()

function cacheFor<T>(map: WeakMap<ClientAssets, Map<string, T>>, assets: ClientAssets) {
  let cache = map.get(assets)
  if (!cache) {
    cache = new Map()
    map.set(assets, cache)
  }
  return cache
}

/** A block state's geometry, corner at the origin; empty when the assets don't know it. */
export function blockGeometry(
  assets: ClientAssets,
  state: string,
  defaults: Record<string, string> = {},
): Promise<BuiltGroup[]> {
  const cache = cacheFor(blocks, assets)
  const key = `${state}|${JSON.stringify(defaults)}`
  let pending = cache.get(key)
  if (!pending) {
    pending = (async () => {
      const placements = await blockPlacements(assets, state, defaults)
      const tint = tintFor(state)
      const baked = await Promise.all(
        placements.map(async (placement) => {
          const model = await resolveModel(assets, placement.model)
          if (!model) return []
          return buildElements(model.elements, model.textures, {
            tint,
            transform: placementMatrix(placement),
          })
        }),
      )
      return baked.flat()
    })()
    cache.set(key, pending)
  }
  return pending
}

export interface ItemGeometry {
  groups: BuiltGroup[]
  /** The model's display transform for the context, then centred on the entity. */
  matrix: THREE.Matrix4
}

export function itemGeometry(
  assets: ClientAssets,
  item: string,
  context: string,
): Promise<ItemGeometry | null> {
  const cache = cacheFor(items, assets)
  const key = `${item}#${context}`
  let pending = cache.get(key)
  if (!pending) {
    pending = (async () => {
      const model = await resolveModel(assets, await itemModelRef(assets, item))
      if (!model) return null
      const groups = isSpriteModel(model)
        ? buildSprite(spriteLayers(model))
        : buildElements(model.elements, model.textures, { tint: tintFor(item) })
      if (groups.length === 0) return null
      return { groups, matrix: itemMatrix(model.display[context]) }
    })()
    cache.set(key, pending)
  }
  return pending
}

/**
 * A pack's block look drawn as its item: the game's `block/cube`, with the
 * block display transforms it inherits, its faces bound to [faces] (each
 * group's `texture` is then whatever the caller gave: a project file's URL).
 */
export function blockLookGeometry(
  assets: ClientAssets,
  faces: Record<string, string>,
  context: string,
): Promise<ItemGeometry | null> {
  const cache = cacheFor(items, assets)
  const key = `look:${JSON.stringify(faces)}#${context}`
  let pending = cache.get(key)
  if (!pending) {
    pending = (async () => {
      const model = await resolveModel(assets, 'minecraft:block/cube')
      if (!model) return null
      const groups = buildElements(model.elements, { ...model.textures, ...faces })
      if (groups.length === 0) return null
      return { groups, matrix: itemMatrix(model.display[context]) }
    })()
    cache.set(key, pending)
  }
  return pending
}

/**
 * Vanilla applies the display transform about the model centre, then draws
 * the item centred on the entity: the transform, then a half-block shift.
 */
function itemMatrix(entry: DisplayEntry | undefined): THREE.Matrix4 {
  const matrix = new THREE.Matrix4()
  if (entry) {
    const [tx, ty, tz] = entry.translation ?? [0, 0, 0]
    const [rx, ry, rz] = entry.rotation ?? [0, 0, 0]
    const [sx, sy, sz] = entry.scale ?? [1, 1, 1]
    matrix.compose(
      new THREE.Vector3(tx / 16, ty / 16, tz / 16),
      new THREE.Quaternion().setFromEuler(
        new THREE.Euler(
          THREE.MathUtils.degToRad(rx),
          THREE.MathUtils.degToRad(ry),
          THREE.MathUtils.degToRad(rz),
          'XYZ',
        ),
      ),
      new THREE.Vector3(sx, sy, sz),
    )
  }
  return matrix.multiply(new THREE.Matrix4().makeTranslation(-0.5, -0.5, -0.5))
}

const textures = new Map<string, THREE.Texture>()
let missing: THREE.Texture | null = null

/** The magenta/black chequer Minecraft draws for a missing texture. */
function missingTexture(): THREE.Texture {
  if (missing) return missing
  const canvas = document.createElement('canvas')
  canvas.width = 16
  canvas.height = 16
  const context = canvas.getContext('2d')
  if (context) {
    context.fillStyle = '#000'
    context.fillRect(0, 0, 16, 16)
    context.fillStyle = '#f800f8'
    context.fillRect(0, 0, 8, 8)
    context.fillRect(8, 8, 8, 8)
  }
  missing = pixelated(new THREE.CanvasTexture(canvas))
  return missing
}

function pixelated(texture: THREE.Texture): THREE.Texture {
  texture.magFilter = THREE.NearestFilter
  texture.minFilter = THREE.NearestFilter
  texture.generateMipmaps = false
  texture.colorSpace = THREE.SRGBColorSpace
  return texture
}

/** A texture with Minecraft's filtering; animated strips show their first frame. */
export function loadTexture(assets: ClientAssets, textureId: string): THREE.Texture {
  const url = assets.textureUrl(textureId)
  const existing = textures.get(url)
  if (existing) return existing
  const texture = new THREE.TextureLoader().load(
    url,
    (loaded) => {
      const image = loaded.image as HTMLImageElement
      if (image.height > image.width && image.height % image.width === 0) {
        const frames = image.height / image.width
        loaded.repeat.set(1, 1 / frames)
        loaded.offset.set(0, 1 - 1 / frames)
        loaded.needsUpdate = true
      }
    },
    undefined,
    () => {
      texture.image = missingTexture().image as HTMLImageElement
      texture.needsUpdate = true
    },
  )
  pixelated(texture)
  textures.set(url, texture)
  return texture
}
