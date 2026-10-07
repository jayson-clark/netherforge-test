import { describe, expect, it } from 'vitest'
import type { CentityFile } from '@/core/format'
import { exampleFiles } from '@/testing/fixtures'
import {
  addAnimation,
  addNode,
  childrenOf,
  deleteAnimation,
  deleteKey,
  deleteNode,
  duplicateNode,
  moveKey,
  renameNode,
  reparent,
  setChannel,
  setEasing,
  setKeyframe,
} from './ops'

const tower = () => JSON.parse(exampleFiles['centities/tower/centity.json']!) as CentityFile

describe('centity ops', () => {
  it('adds nodes with fresh names', () => {
    const file = tower()
    expect(addNode(file, 'root')).toBe('node')
    expect(addNode(file, 'root')).toBe('node_2')
    expect(childrenOf(file, 'root')).toEqual(['node', 'node_2', 'top'])
  })

  it('renames a node with its children and tracks', () => {
    const file = tower()
    renameNode(file, 'top', 'cap')
    expect(file.nodes!.cap).toBeDefined()
    expect(file.nodes!.top).toBeUndefined()
    expect(file.nodes!.flag!.parent).toBe('cap')
    expect(Object.keys(file.animations!.spin!.tracks!)).toEqual(['cap'])
  })

  it('deletes a subtree and its tracks', () => {
    const file = tower()
    deleteNode(file, 'top')
    expect(Object.keys(file.nodes!)).toEqual(['root'])
    expect(file.animations!.spin!.tracks).toEqual({})
  })

  it('duplicates a node with its subtree beside it, without its tracks', () => {
    const file = tower()
    expect(duplicateNode(file, 'top')).toBe('top_2')
    expect(file.nodes!.top_2).toEqual({ ...file.nodes!.top, parent: 'root' })
    expect(file.nodes!.flag_2!.parent).toBe('top_2')
    expect(childrenOf(file, 'root')).toEqual(['top', 'top_2'])
    expect(Object.keys(file.animations!.spin!.tracks!)).toEqual(['top'])
    // A copy of a copy is named from the original, not top_2_2.
    expect(duplicateNode(file, 'top_2')).toBe('top_3')
    expect(duplicateNode(file, 'nothing')).toBeNull()
  })

  it('deletes clips, and the animations key with the last one', () => {
    const file = tower()
    const clip = addAnimation(file)
    deleteAnimation(file, clip)
    expect(Object.keys(file.animations!)).toEqual(['spin'])
    deleteAnimation(file, 'spin')
    expect(file.animations).toBeUndefined()
  })

  it('refuses a cycle', () => {
    const file = tower()
    expect(reparent(file, 'root', 'flag')).toBe(false)
    expect(reparent(file, 'flag', 'root')).toBe(true)
    expect(reparent(file, 'flag', null)).toBe(true)
    expect(file.nodes!.flag!.parent).toBeUndefined()
  })

  it('leaves default transform values out', () => {
    const file = tower()
    const top = file.nodes!.top!
    setChannel(top, 'translation', [0, 0, 0])
    expect(top.transform).toBeUndefined()
    setChannel(top, 'scale', [2, 2, 2])
    expect(top.transform).toEqual({ scale: [2, 2, 2] })
  })

  it('edits keyframes', () => {
    const clip = tower().animations!.spin!
    setKeyframe(clip, 'top', 'rotation', 0.25, [0, 60, 0])
    expect(clip.tracks!.top!.rotation!.map((k) => k.time)).toEqual([0, 0.25, 0.5, 1, 1.5])
    setKeyframe(clip, 'top', 'rotation', 0.25, [0, 70, 0])
    expect(clip.tracks!.top!.rotation![1]!.value).toEqual([0, 70, 0])

    const index = moveKey(clip, 'top', 'rotation', 1, 1.2)
    expect(index).toBe(3)
    expect(clip.tracks!.top!.rotation![3]!.time).toBe(1.2)

    setEasing(clip, 'top', 'rotation', 3, 'step')
    expect(clip.tracks!.top!.rotation![3]!.easing).toBe('step')
    setEasing(clip, 'top', 'rotation', 3, 'linear')
    expect(clip.tracks!.top!.rotation![3]!.easing).toBeUndefined()

    for (let i = 4; i >= 0; i -= 1) deleteKey(clip, 'top', 'rotation', i)
    expect(clip.tracks).toEqual({})
  })
})
