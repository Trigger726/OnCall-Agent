import { beforeEach, test, mock } from 'node:test'
import assert from 'node:assert/strict'
import { build } from 'esbuild'
import { fileURLToPath } from 'node:url'

const built = await build({ entryPoints: [fileURLToPath(new URL('../src/services/api.ts', import.meta.url))],
  bundle: true, write: false, platform: 'node', format: 'esm' })
const { api } = await import(`data:text/javascript;base64,${Buffer.from(built.outputFiles[0].text).toString('base64')}`)
let token, expired
beforeEach(() => {
  mock.restoreAll(); token = 'current-session'; expired = 0
  globalThis.localStorage = { getItem: () => token }
  globalThis.window = new EventTarget()
  window.addEventListener('opspilot-auth-expired', () => expired++)
})
test('incorrect current password preserves a still-valid session and exposes credential error', async () => {
  mock.method(globalThis, 'fetch', async () => Response.json({success:false,error:{code:'AUTHENTICATION_FAILED',message:'用户名或密码错误'}},{status:401}))
  await assert.rejects(api('/auth/password',{method:'POST',body:'{}'}), {code:'AUTHENTICATION_FAILED',status:401})
  assert.equal(expired,0)
})
test('a revoked password command expires authentication without retrying', async () => {
  const fetcher = mock.method(globalThis, 'fetch', async () => Response.json({success:false,error:{code:'AUTHENTICATION_REQUIRED',message:'已撤销'}},{status:401}))
  await assert.rejects(api('/auth/password',{method:'POST',body:'{}'}), {code:'AUTHENTICATION_REQUIRED',status:401})
  assert.equal(expired,1); assert.equal(fetcher.mock.callCount(),1)
})
test('ordinary protected 401 still expires even with a credential error body', async () => {
  mock.method(globalThis, 'fetch', async () => Response.json({success:false,error:{code:'AUTHENTICATION_FAILED'}},{status:401}))
  await assert.rejects(api('/incidents'), {code:'AUTHENTICATION_REQUIRED'})
  assert.equal(expired,1)
})
test('a delayed old-session 401 cannot sign out a newly signed-in identity', async () => {
  mock.method(globalThis, 'fetch', async () => {
    token='new-session'; return Response.json({success:false,error:{code:'AUTHENTICATION_REQUIRED'}},{status:401})
  })
  await assert.rejects(api('/auth/me'), {status:401})
  assert.equal(expired,0)
})
test('malformed 401 remains authentication failure, not an invalid-format success', async () => {
  mock.method(globalThis, 'fetch', async () => new Response('not JSON',{status:401}))
  await assert.rejects(api('/auth/me'), {code:'AUTHENTICATION_REQUIRED'})
  assert.equal(expired,1)
})

const serviceBuild = await build({ entryPoints: [fileURLToPath(new URL('../src/services/authSessions.ts', import.meta.url))],
  bundle:true, write:false, platform:'node', format:'esm' })
const sessions = await import(`data:text/javascript;base64,${Buffer.from(serviceBuild.outputFiles[0].text).toString('base64')}`)
test('password policy counts Unicode codepoints and rejects UTF8 truncation, invalid Unicode and NUL', () => {
  const check = next => sessions.passwordChangeError('valid current',next,next)
  assert.equal(check('界'.repeat(24)),'')
  assert.equal(check('😀'.repeat(15)),'')
  assert.ok(check('界'.repeat(25)))
  assert.ok(check('😀'.repeat(14)))
  assert.ok(check('x'.repeat(14)+'\uD800'))
  assert.ok(check('x'.repeat(14)+'\0'))
  assert.ok(check(' '.repeat(15)))
})
test('confirmation and unchanged passwords fail without trimming legitimate input', () => {
  const value='a valid passphrase  '
  assert.equal(sessions.passwordChangeError('current',value,value),'')
  assert.ok(sessions.passwordChangeError('current',value,value.trim()))
  assert.ok(sessions.passwordChangeError(value,value,value))
})
test('self-session commands send exact payload once with bounded signal and no target account', async () => {
  const calls=[]
  mock.method(globalThis,'fetch',async (url,init) => {
    calls.push({url,init}); return Response.json({success:true,data:{reauthenticationRequired:true,scope:'ALL_ISSUED_SESSIONS'}})
  })
  await sessions.changeOwnSessions('password',{currentPassword:' current ',newPassword:' new exact password '})
  await sessions.changeOwnSessions('logout-all')
  assert.deepEqual(JSON.parse(calls[0].init.body),{currentPassword:' current ',newPassword:' new exact password '})
  assert.equal(calls[1].init.body,undefined)
  assert.ok(calls.every(c=>c.init.method==='POST'&&c.init.signal instanceof AbortSignal))
  assert.equal(calls.length,2)
})
test('ambiguous mutation is not retried or called successful and no password draft is stored', async () => {
  const fetcher=mock.method(globalThis,'fetch',async () => {throw new TypeError('response lost')})
  await assert.rejects(sessions.changeOwnSessions('password',{currentPassword:'old',newPassword:'new'}),TypeError)
  assert.equal(fetcher.mock.callCount(),1)
})
test('malformed successful mutation contract is uncertainty, not a successful logout claim', async () => {
  mock.method(globalThis,'fetch',async () => Response.json({success:true,data:{reauthenticationRequired:false,scope:'ONE_SESSION'}}))
  await assert.rejects(sessions.changeOwnSessions('logout-all'),/无法确认/)
})
