import test from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync, verify } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';

test('Workers runtime issues an ES256 token accepted by independent signature verification', async () => {
  const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const issuer = readFileSync(new URL('./issuer.mjs', import.meta.url), 'utf8');
  const worker = readFileSync(new URL('./worker.mjs', import.meta.url), 'utf8')
    .replace("import { createTokenIssuer } from './issuer.mjs';", '');
  const mf = new Miniflare(convertV4MiniflareOptions({
    modules: true,
    compatibilityDate: '2026-09-06',
    compatibilityFlags: ['nodejs_compat'],
    script: issuer + '\n' + worker,
    bindings: {
      APPLE_PRIVATE_KEY: privateKey.export({ type: 'pkcs8', format: 'pem' }),
      APPLE_KEY_ID: 'ABCDEFGHIJ',
      APPLE_TEAM_ID: 'KLMNOPQRST',
    },
    ratelimits: {
      TOKEN_LIMITER: { namespace_id: '2026090601', simple: { limit: 300, period: 60 } },
    },
  }));
  try {
    const response = await mf.dispatchFetch('https://example.test/token');
    assert.equal(response.status, 200);
    const { developerToken } = await response.json();
    const [header, payload, signature] = developerToken.split('.');
    assert.equal(verify('sha256', Buffer.from(`${header}.${payload}`),
      { key: publicKey, dsaEncoding: 'ieee-p1363' }, Buffer.from(signature, 'base64url')), true);
    assert.equal(response.headers.get('cache-control'), 'no-store');
  } finally {
    await mf.dispose();
  }
});
