// test/luwi-remote.test.mjs — 地址 + 令牌拼成真正给 git 的地址 (2026-10-20)
//
// 主人要求令牌单独一格, 而手机侧 git 没有 credential helper —— 所以真正推拉用的地址仍是
// `https://<user>:<token>@host/path`, 由 composeRemoteWithToken 在宿主这一侧拼出来

import test from 'node:test'
import assert from 'node:assert/strict'
import { composeRemoteWithToken, stripRemotePassword } from '../lib/luwi-remote.mjs'

test('no token → the address is returned untouched', () => {
  assert.equal(composeRemoteWithToken('https://github.com/you/repo.git', ''), 'https://github.com/you/repo.git')
  assert.equal(composeRemoteWithToken('', 'tok'), '')
})

test('https without a user gets x-access-token', () => {
  assert.equal(
    composeRemoteWithToken('https://github.com/you/repo.git', 'ghp_abc'),
    'https://x-access-token:ghp_abc@github.com/you/repo.git',
  )
})

test('a user already in the address is kept', () => {
  assert.equal(
    composeRemoteWithToken('https://you@github.com/you/repo.git', 'ghp_abc'),
    'https://you:ghp_abc@github.com/you/repo.git',
  )
})

test('an address that already carries a password is left alone', () => {
  const url = 'https://u:p@github.com/you/repo.git'
  assert.equal(composeRemoteWithToken(url, 'tok'), url)
})

test('ssh / git / local paths are left alone (a token only means HTTPS basic auth)', () => {
  for (const remote of ['git@github.com:you/repo.git', 'ssh://git@host/r.git', 'git://127.0.0.1:9418/r.git', 'D:/r.git', '/srv/r.git']) {
    assert.equal(composeRemoteWithToken(remote, 'tok'), remote)
  }
})

test('the token is URL-encoded', () => {
  const composed = composeRemoteWithToken('https://host/you/repo.git', 'a/b c')
  assert.equal(composed, 'https://x-access-token:a%2Fb%20c@host/you/repo.git')
})

test('the port survives', () => {
  assert.equal(
    composeRemoteWithToken('https://host:8443/you/repo.git', 'tok'),
    'https://x-access-token:tok@host:8443/you/repo.git',
  )
})

test('stripRemotePassword never shows the token', () => {
  assert.equal(stripRemotePassword('https://x-access-token:secret@host/you/repo.git'), 'https://x-access-token@host/you/repo.git')
  assert.equal(stripRemotePassword('https://host/you/repo.git'), 'https://host/you/repo.git')
  assert.equal(stripRemotePassword('git@github.com:you/repo.git'), 'git@github.com:you/repo.git')
})
