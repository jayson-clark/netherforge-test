import js from '@eslint/js'
import boundaries from 'eslint-plugin-boundaries'
import reactHooks from 'eslint-plugin-react-hooks'
import globals from 'globals'
import tseslint from 'typescript-eslint'

/**
 * `src/`'s layers, top to bottom (the editor-ui skill's "Where things are"):
 * each imports only from itself and the layers above it.
 */
const LAYERS = ['core', 'ui', 'state', 'minecraft', 'editors', 'workbench', 'app']

export default tseslint.config(
  // generated/: tauri-specta's output, checked by the generated-file check instead.
  {
    ignores: [
      'dist',
      'src-tauri',
      'test-results',
      'playwright-report',
      'src/core/backend/generated',
    ],
  },
  {
    files: ['**/*.{ts,tsx}'],
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    languageOptions: {
      globals: globals.browser,
      // Type-aware rules: a promise nobody awaits or handles is a bug here.
      parserOptions: { projectService: true, tsconfigRootDir: import.meta.dirname },
    },
    plugins: { 'react-hooks': reactHooks },
    rules: {
      ...reactHooks.configs.recommended.rules,
      '@typescript-eslint/no-floating-promises': 'error',
      '@typescript-eslint/no-misused-promises': [
        'error',
        { checksVoidReturn: { attributes: false } },
      ],
    },
  },
  {
    files: ['src/**/*.{ts,tsx}'],
    plugins: { boundaries },
    settings: {
      'import/resolver': { typescript: { project: `${import.meta.dirname}/tsconfig.app.json` } },
      'boundaries/root-path': import.meta.dirname,
      'boundaries/elements': [
        ...LAYERS.map((layer) => ({ type: layer, pattern: `src/${layer}`, partialMatch: false })),
        { type: 'testing', pattern: 'src/testing', partialMatch: false },
      ],
      'boundaries/files': [
        { category: 'test', pattern: '**/*.test.{ts,tsx}' },
        // The memory backend plays the outside world for vitest and Playwright
        // (a captured structure is a fixture's NBT): test support that lives
        // beside the Backend contract it's held to.
        { category: 'fake', pattern: 'src/core/backend/{memory,seed,seeded}.ts' },
      ],
    },
    rules: {
      'boundaries/dependencies': [
        'error',
        {
          default: 'disallow',
          policies: [
            ...LAYERS.map((layer, index) => ({
              from: { element: { type: layer } },
              allow: { to: { element: { types: { anyOf: LAYERS.slice(0, index + 1) } } } },
            })),
            // Fixtures are built with what they stand in for; the shared set-up
            // (testing/workspace.ts) makes the app's stores.
            {
              from: { element: { type: 'testing' } },
              allow: {
                to: { element: { types: { anyOf: ['core', 'state', 'minecraft', 'testing'] } } },
              },
            },
            {
              from: { file: { categories: 'fake' } },
              allow: { to: { element: { types: { anyOf: ['minecraft', 'testing'] } } } },
            },
            // A test may make the whole app and use any fixture.
            {
              from: { file: { categories: 'test' } },
              allow: { to: { element: { types: { anyOf: [...LAYERS, 'testing'] } } } },
            },
          ],
        },
      ],
    },
  },
)
