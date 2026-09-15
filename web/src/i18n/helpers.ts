/**
 * Message catalogue helpers. These operate on plain (flattened) message maps so
 * both the runtime i18n setup and the plain-node checker can share the same
 * vocabulary of keys and `{placeholder}` names.
 */

export type Messages = Record<string, unknown>
export type FlatMessages = Record<string, string>

/** Flattens `{ a: { b: 'x' } }` into `{ 'a.b': 'x' }`. Non-string leaves are reported. */
export function flattenMessages(
  input: Messages,
  prefix = '',
): { messages: FlatMessages; invalid: string[] } {
  const messages: FlatMessages = {}
  const invalid: string[] = []

  const walk = (node: unknown, path: string): void => {
    if (node !== null && typeof node === 'object' && !Array.isArray(node)) {
      for (const [key, value] of Object.entries(node as Messages)) {
        walk(value, path ? `${path}.${key}` : key)
      }
      return
    }
    if (typeof node === 'string') {
      messages[path] = node
      return
    }
    invalid.push(path || '(root)')
  }

  walk(input, prefix)
  return { messages, invalid }
}

/** Extracts the distinct `{name}` placeholders used by a message. */
export function extractPlaceholders(message: string): string[] {
  const found = new Set<string>()
  const pattern = /\{([a-zA-Z0-9_]+)\}/g
  let match = pattern.exec(message)
  while (match !== null) {
    found.add(match[1] as string)
    match = pattern.exec(message)
  }
  return [...found].sort()
}

/** Interpolates `{name}` placeholders without pulling in the i18n runtime. */
export function interpolateMessage(message: string, params: Record<string, unknown> = {}): string {
  return message.replace(/\{([a-zA-Z0-9_]+)\}/g, (raw, name: string) =>
    Object.prototype.hasOwnProperty.call(params, name) ? String(params[name]) : raw,
  )
}

export const FALLBACK_LOCALE = 'en-US'
