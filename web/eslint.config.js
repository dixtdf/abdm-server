import js from '@eslint/js'
import globals from 'globals'
import pluginVue from 'eslint-plugin-vue'
import tseslint from 'typescript-eslint'

/**
 * Formatting-only rules from `eslint-plugin-vue` are disabled on purpose: this
 * project formats by hand and `npm run lint` must stay about correctness, not
 * whitespace. `vue/no-bare-strings-in-template` stays enabled so any hardcoded
 * user visible text fails lint.
 */
const sharedRules = {
  // --- i18n guard -------------------------------------------------------
  'vue/no-bare-strings-in-template': [
    'error',
    {
      allowlist: [
        '(',
        ')',
        ',',
        '.',
        '&',
        '+',
        '-',
        '=',
        '*',
        '/',
        '#',
        '%',
        '!',
        '?',
        ':',
        '[',
        ']',
        '{',
        '}',
        '<',
        '>',
        '•',
        '—',
        '·',
        ' ',
        '$',
        '^',
        '~',
        '|',
      ],
      directives: ['v-text'],
    },
  ],

  // --- correctness ------------------------------------------------------
  'vue/require-explicit-emits': 'error',
  'vue/no-mutating-props': 'error',
  'vue/no-v-html': 'error',
  'vue/component-name-in-template-casing': ['error', 'PascalCase'],
  'vue/multi-word-component-names': 'off',
  '@typescript-eslint/no-unused-vars': [
    'error',
    { argsIgnorePattern: '^_', varsIgnorePattern: '^_', caughtErrors: 'none' },
  ],
  '@typescript-eslint/no-explicit-any': 'error',
  'no-console': ['warn', { allow: ['warn', 'error', 'info', 'debug'] }],
  '@typescript-eslint/ban-ts-comment': 'off',

  // --- formatting (delegated to the author) -----------------------------
  'vue/max-attributes-per-line': 'off',
  'vue/singleline-html-element-content-newline': 'off',
  'vue/multiline-html-element-content-newline': 'off',
  'vue/html-indent': 'off',
  'vue/html-closing-bracket-newline': 'off',
  'vue/html-self-closing': 'off',
  'vue/attributes-order': 'off',
  'vue/attribute-hyphenation': 'off',
  'vue/first-attribute-linebreak': 'off',
  'vue/html-quotes': 'off',
  'vue/require-default-prop': 'off',
  'vue/one-component-per-file': 'off',
}

export default tseslint.config(
  {
    ignores: ['dist/**', 'node_modules/**', 'coverage/**', '.cowork-temp/**'],
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...pluginVue.configs['flat/recommended'],
  {
    files: ['**/*.{js,mjs,cjs}'],
    languageOptions: {
      sourceType: 'module',
      globals: { ...globals.node },
    },
  },
  {
    files: ['**/*.{ts,vue}'],
    languageOptions: {
      globals: { ...globals.browser, ...globals.node },
    },
    rules: sharedRules,
  },
  {
    files: ['**/*.vue'],
    languageOptions: {
      parserOptions: {
        parser: tseslint.parser,
        extraFileExtensions: ['.vue'],
        ecmaVersion: 'latest',
        sourceType: 'module',
      },
    },
    rules: sharedRules,
  },
)
