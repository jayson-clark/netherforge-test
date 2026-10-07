/**
 * The JSON Schemas `format` generates, bundled at build time. The editor
 * writes them into each project's `.netherforge/schema/` on open, which is where
 * every file's `$schema` points, so VS Code and other editors validate
 * project files too.
 */
const modules = import.meta.glob(
  '/node_modules/@netherforge/format/build/generated-contract/schema/*.json',
  {
    query: '?raw',
    import: 'default',
    eager: true,
  },
) as Record<string, string>

/** File name (`centity.schema.json`) → text. */
export const schemaFiles: Record<string, string> = Object.fromEntries(
  Object.entries(modules).map(([key, text]) => [key.slice(key.lastIndexOf('/') + 1), text]),
)

export const SCHEMA_FOLDER = '.netherforge/schema'
