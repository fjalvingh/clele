import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'
import { defineConfig, globalIgnores } from 'eslint/config'

export default defineConfig([
  globalIgnores(['dist']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: {
      ecmaVersion: 2020,
      globals: globals.browser,
    },
    rules: {
      // Number spinners are banned in this app: they cannot be cleared while typing and the
      // up/down buttons are useless for the counts we ask for. Use components/NumberInput.
      'no-restricted-syntax': [
        'error',
        {
          selector:
            'JSXAttribute[name.name="type"][value.value="number"]',
          message:
            'Do not use <input type="number"> — it renders spinner buttons and fights the value being retyped. Use NumberInput / NumberTextInput / NumberField from components/NumberInput.',
        },
      ],
    },
  },
])
