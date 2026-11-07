// M1 自检脚本：验证从 APK lib/ 启动的 Node 能工作。
// 由 RuntimeHost 启动，工作目录与临时目录均由宿主注入。
import http from 'node:http'
import { writeFileSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'

const base = process.env.HOME || process.cwd()
const file = join(base, `selftest-node-${process.pid}.txt`)

console.log(`NODE_VERSION=${process.version}`)
console.log(`NODE_ARCH=${process.arch}`)
console.log(`NODE_PLATFORM=${process.platform}`)
console.log(`NODE_HOME=${base}`)
console.log(`NODE_EXEC=${process.execPath}`)

writeFileSync(file, 'hello-from-node\n')
const back = readFileSync(file, 'utf8').trim()
console.log(`FILE_WRITE_READ=${back}`)
console.log(`FILE_SIZE=${statSync(file).size}`)

const server = http.createServer((req, res) => {
  res.writeHead(200, { 'content-type': 'text/plain' })
  res.end('ok-from-node')
})

server.listen(0, '127.0.0.1', async () => {
  const { port } = server.address()
  console.log(`HTTP_LISTEN=127.0.0.1:${port}`)
  try {
    const res = await fetch(`http://127.0.0.1:${port}/ping`)
    const body = await res.text()
    console.log(`HTTP_SELF_FETCH=${res.status}:${body}`)
  } catch (error) {
    console.log(`HTTP_SELF_FETCH=FAILED:${error.message}`)
    process.exitCode = 1
  } finally {
    server.close()
  }
})
