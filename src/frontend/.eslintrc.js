// =====================================================================
// ESLint 基础配置（L1）
// 目标：约束未定义变量/明显错误，风格规则匹配现有代码（单引号 + 分号）
// =====================================================================
module.exports = {
  root: true,
  env: {
    node: true,
    browser: true,
    es2021: true
  },
  extends: [
    'plugin:vue/recommended',
    'eslint:recommended'
  ],
  parserOptions: {
    parser: 'babel-eslint',
    ecmaVersion: 2021
  },
  rules: {
    // 风格：与现有代码一致（单引号、分号、无尾逗号）
    'semi': ['error', 'always'],
    'quotes': ['error', 'single'],
    'comma-dangle': ['error', 'never'],
    'no-unused-vars': 'warn',
    'no-console': 'off',
    'no-debugger': 'off',
    // Vue：组件名放宽（现有单文件命名不强制多词）
    'vue/multi-word-component-names': 'off',
    'vue/no-unused-components': 'warn',
    'vue/max-attributes-per-line': 'off',
    'vue/singleline-html-element-content-newline': 'off',
    'vue/html-closing-bracket-newline': 'off',
    'vue/html-self-closing': 'off'
  }
}
