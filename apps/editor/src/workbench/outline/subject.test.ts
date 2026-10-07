import { describe, expect, it } from 'vitest'
import { subjectOf } from './subject'

describe('subjectOf', () => {
  it("is the resource a file belongs to, from its own file or a script's", () => {
    expect(subjectOf('centities/tower/centity.json')).toEqual({
      kind: 'centity',
      id: 'tower',
      location: 'centities/tower',
      main: 'centities/tower/centity.json',
      hasFiles: true,
    })
    expect(subjectOf('centities/tower/lib/steps.lua')?.main).toBe('centities/tower/centity.json')
    expect(subjectOf('modules/greeter/init.lua')).toMatchObject({ kind: 'module', main: null })
    expect(subjectOf('recipes/ruby.json')).toMatchObject({ kind: 'recipe', hasFiles: false })
    expect(subjectOf('maps/arena')).toMatchObject({ kind: 'map', id: 'arena' })
    expect(subjectOf('netherforge.json')).toBeNull()
    expect(subjectOf(null)).toBeNull()
  })
})
