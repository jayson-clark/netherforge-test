// @vitest-environment node
/// <reference types="vite/client" />
// The bundle under test is built with Vite's import.meta.glob.
import { readdirSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'
import { agentDocFiles, agentDocPaths } from './agentDocs'

describe('agent docs, against the docs folder', () => {
  it("is the docs site's own Markdown, every page and word of it, so the bundle can't drift", async () => {
    const docs = path.resolve(import.meta.dirname, '../../../../docs')
    const sources = ['guide', 'format', 'reference'].flatMap((folder) =>
      readdirSync(path.join(docs, folder))
        .filter((it) => it.endsWith('.md'))
        .map((it) => `${folder}/${it}`),
    )
    expect(agentDocPaths).toEqual(sources.sort())
    const files = await agentDocFiles('1.2.3', null)
    for (const page of sources) {
      expect(files[`.netherforge/docs/${page}`]).toBe(readFileSync(path.join(docs, page), 'utf8'))
    }
  })
})
