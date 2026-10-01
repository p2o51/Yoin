import assert from 'node:assert/strict';
import { generateKeyPairSync, verify } from 'node:crypto';
import { once } from 'node:events';
import test from 'node:test';
import { createTokenIssuer, createTokenServer } from './server.mjs';

const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
const config = { privateKeyPem: privateKey.export({ type: 'pkcs8', format: 'pem' }), keyId: 'TESTKEY123', teamId: 'TESTTEAM12' };

test('ES256 signature verifies and tokens rotate before expiry', () => {
  let now = 1000;
  const issue = createTokenIssuer({ ...config, now: () => now });
  const token = issue();
  const [header, payload, signature] = token.split('.');
  assert.deepEqual(JSON.parse(Buffer.from(header, 'base64url')), { alg: 'ES256', kid: config.keyId });
  assert.deepEqual(JSON.parse(Buffer.from(payload, 'base64url')), { iss: config.teamId, iat: 1000, exp: 4600 });
  assert.equal(verify('sha256', Buffer.from(`${header}.${payload}`), { key: publicKey, dsaEncoding: 'ieee-p1363' }, Buffer.from(signature, 'base64url')), true);
  assert.equal(issue(), token);
  now = 4300;
  assert.notEqual(issue(), token);
});

test('HTTP contract matches Yoin and exposes no private key', async t => {
  const server = createTokenServer(createTokenIssuer(config));
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(() => server.close());
  const base = `http://127.0.0.1:${server.address().port}`;
  const response = await fetch(`${base}/token`);
  assert.equal(response.status, 200);
  assert.equal(response.headers.get('cache-control'), 'no-store');
  const body = await response.json();
  assert.deepEqual(Object.keys(body), ['developerToken']);
  assert.equal(body.developerToken.includes('PRIVATE KEY'), false);
  assert.equal((await fetch(`${base}/token`, { method: 'POST' })).status, 405);
  assert.equal((await fetch(`${base}/unknown`)).status, 404);
});

test('signing failure returns a generic error', async t => {
  const server = createTokenServer(() => { throw new Error('private data'); });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(() => server.close());
  const response = await fetch(`http://127.0.0.1:${server.address().port}/token`);
  assert.equal(response.status, 503);
  assert.deepEqual(await response.json(), { error: 'token_unavailable' });
});
