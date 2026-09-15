#!/usr/bin/env node
// ---------------------------------------------------------------------------
// Range-capable static file server for local testing.
//
//   node scripts/test-http-server.mjs <file> [port]
//
// It answers HEAD/GET, honours `Range: bytes=start-end` with 206 +
// Content-Range, and therefore lets the engine's segmented download, connection
// changes and resume be verified without touching a real server. It is also the
// fixture used by `scripts/acceptance-test.ps1`.
// ---------------------------------------------------------------------------
import { createServer } from 'node:http'
import { createReadStream, statSync } from 'node:fs'
import { basename, resolve } from 'node:path'

const [, , fileArg, portArg, ...rest] = process.argv
if (!fileArg) {
  console.error('usage: node scripts/test-http-server.mjs <file> [port] [--throttle <bytes-per-second-per-connection>]')
  process.exit(2)
}

const throttleIndex = rest.indexOf('--throttle')
const throttle = throttleIndex >= 0 ? Number(rest[throttleIndex + 1]) : 0

const file = resolve(fileArg)
const size = statSync(file).size
const port = Number(portArg ?? 9100)

function parseRange(header) {
  if (!header || !header.startsWith('bytes=')) return null
  const [startText, endText] = header.slice(6).split('-')
  const start = startText === '' ? size - Number(endText) : Number(startText)
  const end = endText === '' || endText === undefined ? size - 1 : Number(endText)
  if (Number.isNaN(start) || Number.isNaN(end) || start > end || start < 0) return null
  return { start, end: Math.min(end, size - 1) }
}

// Optional per-connection throttle: makes a download slow enough to watch (and to
// pause or change the connection count while it runs).
function pipeThrottled(stream, res) {
  if (!throttle) {
    stream.pipe(res)
    return
  }
  stream.on('data', (chunk) => {
    stream.pause()
    res.write(chunk)
    // Always wait, otherwise the socket simply drains at full speed on localhost.
    setTimeout(() => stream.resume(), Math.max(1, Math.ceil((chunk.length / throttle) * 1000)))
  })
  stream.on('end', () => res.end())
  res.on('close', () => stream.destroy())
}

const server = createServer((req, res) => {
  const name = basename(file)
  const headers = {
    'Accept-Ranges': 'bytes',
    'Content-Type': 'application/octet-stream',
    'Content-Disposition': `attachment; filename="${name}"`,
    ETag: `"${size}-${name}"`,
  }
  if (req.method === 'HEAD') {
    res.writeHead(200, { ...headers, 'Content-Length': String(size) })
    res.end()
    return
  }
  const range = parseRange(req.headers.range)
  if (range) {
    const length = range.end - range.start + 1
    res.writeHead(206, {
      ...headers,
      'Content-Length': String(length),
      'Content-Range': `bytes ${range.start}-${range.end}/${size}`,
    })
    if (req.method === 'GET') {
      pipeThrottled(createReadStream(file, { start: range.start, end: range.end }), res)
    } else {
      res.end()
    }
    return
  }
  res.writeHead(200, { ...headers, 'Content-Length': String(size) })
  if (req.method === 'GET') {
    pipeThrottled(createReadStream(file), res)
  } else {
    res.end()
  }
})

server.listen(port, '127.0.0.1', () => {
  console.log(`[test-http-server] serving ${file} (${size} bytes) on http://127.0.0.1:${port}/`)
  if (throttle) console.log(`[test-http-server] throttled to ${throttle} bytes/s per connection`)
  console.log(`[test-http-server] download url: http://127.0.0.1:${port}/${encodeURIComponent(basename(file))}`)
})

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => server.close(() => process.exit(0)))
}
