import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    globals: true,
    environment: 'node',
    setupFiles: [],
  },
  resolve: {
    alias: {
      '@': `${import.meta.dirname}/src`,
    },
  },
});
