import test from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync, verify } from 'node:crypto';
import worker from './worker.mjs';

test('Worker signs the Android contract with a valid signature', async () => {
  const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const env = { APPLE_PRIVATE_KEY: privateKey.export({ type: 'pkcs8', format: 'pem' }),
    APPLE_KEY_ID: 'TESTKEY123', APPLE_TEAM_ID: 'TESTTEAM12', TOKEN_LIMITER: { limit: async () => ({ success: true }) } };
  const response = await worker.fetch(new Request('https://example.test/token'), env);
  assert.equal(response.status, 200);
  const { developerToken } = await response.json();
  const [a, b, s] = developerToken.split('.');
  assert.equal(verify('sha256', Buffer.from(`${a}.${b}`), { key: publicKey, dsaEncoding: 'ieee-p1363' }, Buffer.from(s, 'base64url')), true);
});

test('Worker rate-limits before signing and fails closed without secrets', async () => {
  const request = new Request('https://example.test/token');
  const limited = await worker.fetch(request, { TOKEN_LIMITER: { limit: async () => ({ success: false }) } });
  assert.equal(limited.status, 429);
  const failed = await worker.fetch(request, { TOKEN_LIMITER: { limit: async () => ({ success: true }) } });
  assert.equal(failed.status, 503);
  assert.deepEqual(await failed.json(), { error: 'token_unavailable' });
});
