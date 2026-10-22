// sidecar v2 的环回冒烟: 两个 lwiroh.exe + 两个"JS 侧"
//   1. A 的数据面拨 B 的 ticket, 入站回调落到本脚本的 LW_INBOUND 端口
//   2. A -> B 发 1 MiB 随机字节, B -> A 回 1 MiB, 两端逐字节比对 sha256
// 跑到这一步就说明 T1 的三条 (数据面 TCP / 入站回调 / 双平台二进制) 都通了
//
// 用法: node smoke.mjs [--exe <path>] [--bytes 1048576]
import { spawn } from 'node:child_process'
import { createHash, randomBytes } from 'node:crypto'
import { createServer, connect } from 'node:net'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { request } from 'node:http'

const here = dirname(fileURLToPath(import.meta.url))
const args = process.argv.slice(2)
const argOf = (name, fallback) => {
  const index = args.indexOf(name)
  return index >= 0 && args[index + 1] ? args[index + 1] : fallback
}
const exe = resolve(argOf('--exe', join(here, '..', 'win32-x64', 'lwiroh.exe')))
const bytes = Number(argOf('--bytes', '1048576'))
const token = randomBytes(12).toString('hex')

const log = (...parts) => console.log(`[smoke ${new Date().toISOString().slice(11, 19)}]`, ...parts)

function waitReady(proc, name, timeoutMs = 30000) {
  return new Promise((resolvePromise, reject) => {
    let buffer = ''
    const timer = setTimeout(() => reject(new Error(`${name}: no READY in ${timeoutMs}ms (output: ${buffer.slice(-400)})`)), timeoutMs)
    proc.stdout.on('data', (chunk) => {
      buffer += chunk.toString()
      const line = buffer.split(/\r?\n/).find((item) => item.startsWith('READY '))
      if (!line) return
      clearTimeout(timer)
      const fields = Object.fromEntries(line.slice(6).split(' ').map((pair) => pair.split('=')))
      resolvePromise({ port: Number(fields.port), dataPort: Number(fields.dataPort) })
    })
    proc.stderr.on('data', (chunk) => {
      const text = chunk.toString().trim()
      if (text) log(`${name} stderr:`, text)
    })
    proc.on('exit', (code) => reject(new Error(`${name} exited before READY (code ${code})`)))
  })
}

function startSidecar(name, inboundPort) {
  const env = {
    ...process.env,
    LW_TOKEN: token,
    LW_PORT: '0',
    LW_DATA_PORT: '0',
    LW_IDLE_MS: '0',
    LW_ONLINE_MS: '15000',
  }
  if (inboundPort) env.LW_INBOUND = String(inboundPort)
  const proc = spawn(exe, ['daemon'], { env, stdio: ['ignore', 'pipe', 'pipe'] })
  proc.stdout.setEncoding('utf8')
  log(`${name} pid=${proc.pid} exe=${exe}`)
  return { proc, ready: waitReady(proc, name) }
}

function httpGet(port, path, bearer) {
  return new Promise((resolvePromise, reject) => {
    const req = request({ host: '127.0.0.1', port, path, method: 'GET', headers: bearer ? { authorization: `Bearer ${bearer}` } : {} }, (res) => {
      let body = ''
      res.setEncoding('utf8')
      res.on('data', (chunk) => (body += chunk))
      res.on('end', () => {
        try {
          resolvePromise(JSON.parse(body))
        } catch (error) {
          reject(new Error(`${path}: ${error.message}: ${body}`))
        }
      })
    })
    req.on('error', reject)
    req.end()
  })
}

function httpPost(port, path, bearer) {
  return new Promise((resolvePromise, reject) => {
    const req = request({ host: '127.0.0.1', port, path, method: 'POST', headers: { authorization: `Bearer ${bearer}`, 'content-length': 0 } }, (res) => {
      let body = ''
      res.setEncoding('utf8')
      res.on('data', (chunk) => (body += chunk))
      res.on('end', () => resolvePromise({ status: res.statusCode, body }))
    })
    req.on('error', reject)
    req.end()
  })
}

function readLine(socket, bufferRef) {
  return new Promise((resolvePromise, reject) => {
    const check = () => {
      const index = bufferRef.buffer.indexOf('\n')
      if (index < 0) return false
      const line = bufferRef.buffer.slice(0, index)
      bufferRef.buffer = bufferRef.buffer.slice(index + 1)
      resolvePromise(line)
      return true
    }
    if (check()) return
    const onData = (chunk) => {
      bufferRef.buffer += chunk.toString('utf8')
      if (check()) {
        socket.off('data', onData)
        socket.off('error', onError)
      }
    }
    const onError = (error) => reject(error)
    socket.on('data', onData)
    socket.on('error', onError)
  })
}

function collectExact(socket, size, bufferRef) {
  return new Promise((resolvePromise, reject) => {
    const chunks = []
    let got = 0
    const finish = () => resolvePromise(Buffer.concat(chunks, got))
    if (bufferRef.buffer.length) {
      const take = Math.min(size, bufferRef.buffer.length)
      chunks.push(bufferRef.buffer.subarray(0, take))
      bufferRef.buffer = bufferRef.buffer.subarray(take)
      got += take
      if (got === size) return finish()
    }
    const onData = (chunk) => {
      const take = Math.min(size - got, chunk.length)
      chunks.push(chunk.subarray(0, take))
      got += take
      if (got === size) {
        socket.off('data', onData)
        socket.off('error', onError)
        finish()
      }
    }
    const onError = (error) => reject(error)
    socket.on('data', onData)
    socket.on('error', onError)
  })
}

const sha = (buffer) => createHash('sha256').update(buffer).digest('hex')
const sleep = (ms) => new Promise((done) => setTimeout(done, ms))

async function main() {
  // B 的入站回调端口
  const inbound = createServer()
  await new Promise((done) => inbound.listen(0, '127.0.0.1', done))
  const inboundPort = inbound.address().port
  const inboundSockets = []
  const inboundLines = []
  inbound.on('connection', (socket) => {
    log('inbound callback connected')
    const ref = { buffer: '' }
    inboundSockets.push({ socket, ref })
    inboundLines.push(readLine(socket, ref))
  })

  const a = startSidecar('A', 0)
  const b = startSidecar('B', inboundPort)
  const cleanups = [a, b]

  try {
    const [aReady, bReady] = await Promise.all([a.ready, b.ready])
    log('READY', JSON.stringify({ a: aReady, b: bReady }))
    const aId = await httpGet(aReady.port, '/v1/id', token)
    const bId = await httpGet(bReady.port, '/v1/id', token)
    log('A id', aId.id.slice(0, 16), 'B id', bId.id.slice(0, 16))
    log('B status', JSON.stringify(await httpGet(bReady.port, '/status', token)))

    // A 的数据面: 拨 B, 先拿回一行读数, 之后是字节管道
    const data = connect(aReady.dataPort, '127.0.0.1')
    await new Promise((done, fail) => {
      data.once('connect', done)
      data.once('error', fail)
    })
    const dataRef = { buffer: '' }
    data.write(JSON.stringify({ op: 'connect', to: bId.ticket, token }) + '\n')
    const reply = JSON.parse(await readLine(data, dataRef))
    log('dial reply', JSON.stringify(reply))
    if (!reply.ok) throw new Error(`dial failed: ${reply.error}`)

    const inboundSocket = await Promise.race([
      new Promise((done) => {
        const timer = setInterval(() => {
          if (inboundSockets.length) {
            clearInterval(timer)
            done(inboundSockets[0])
          }
        }, 20)
      }),
      sleep(20000).then(() => {
        throw new Error('no inbound callback within 20s')
      }),
    ])
    const peerLine = JSON.parse(await Promise.race([inboundLines[0], sleep(5000).then(() => { throw new Error('no peer line') })]))
    log('inbound peer line', JSON.stringify(peerLine))
    if (peerLine.peer !== aId.id) throw new Error(`peer mismatch: ${peerLine.peer} != ${aId.id}`)

    // A -> B
    const forward = randomBytes(bytes)
    const forwardStarted = Date.now()
    data.write(forward)
    const receivedByB = await collectExact(inboundSocket.socket, bytes, inboundSocket.ref)
    const forwardMs = Date.now() - forwardStarted
    if (sha(forward) !== sha(receivedByB)) throw new Error('A -> B payload mismatch')
    log(`A -> B ${bytes} bytes ok, ${forwardMs}ms, ${(bytes / 1048576 / (forwardMs / 1000)).toFixed(2)} MiB/s, sha=${sha(forward).slice(0, 16)}`)

    // B -> A
    const backward = randomBytes(bytes)
    const backwardStarted = Date.now()
    inboundSocket.socket.write(backward)
    const receivedByA = await collectExact(data, bytes, dataRef)
    const backwardMs = Date.now() - backwardStarted
    if (sha(backward) !== sha(receivedByA)) throw new Error('B -> A payload mismatch')
    log(`B -> A ${bytes} bytes ok, ${backwardMs}ms, ${(bytes / 1048576 / (backwardMs / 1000)).toFixed(2)} MiB/s, sha=${sha(backward).slice(0, 16)}`)

    data.destroy()
    inboundSocket.socket.destroy()
    await sleep(300)
    log('PASS two sidecars moved', bytes, 'bytes each way, byte-identical')
  } finally {
    for (const sidecar of cleanups) {
      const shutdown = await httpPost(sidecar.ready.port ?? 0, '/v1/shutdown', token).catch(() => null)
      if (shutdown) log('shutdown reply', shutdown.body.trim())
      sidecar.proc.kill()
    }
    inbound.close()
  }
}

main().then(
  () => process.exit(0),
  (error) => {
    console.error('[smoke] FAIL', error)
    process.exit(1)
  },
)
