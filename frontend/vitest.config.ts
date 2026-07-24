import { defineConfig } from 'vitest/config'

// Kept separate from vite.config.ts on purpose: importing vitest's defineConfig
// there drags in vitest's bundled Vite, whose Plugin type clashes with the
// top-level Vite the React/Tailwind plugins use. The unit tests are pure
// functions and need no plugins, so this config carries only the test settings.
export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
})
