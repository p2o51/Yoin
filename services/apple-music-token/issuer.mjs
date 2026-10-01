import { createPrivateKey, sign } from 'node:crypto';
// No third-party runtime dependencies; the private key never leaves this process.
export function createTokenIssuer({ privateKeyPem, keyId, teamId, now = () => Math.floor(Date.now() / 1000) }) {
  if (!/^[A-Z0-9]{10}$/.test(keyId ?? '') || !/^[A-Z0-9]{10}$/.test(teamId ?? '')) {
    throw new Error('Set valid APPLE_KEY_ID and APPLE_TEAM_ID');
  }
  const key = createPrivateKey(privateKeyPem);
  if (key.asymmetricKeyType !== 'ec' || key.asymmetricKeyDetails.namedCurve !== 'prime256v1') {
    throw new Error('The MusicKit key must be an EC P-256 private key');
  }
  let cached;
  let expiresAt = 0;
  const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
  return () => {
    const timestamp = now();
    if (!cached || expiresAt <= timestamp + 300) {
      expiresAt = timestamp + 3600;
      const content = `${encode({ alg: 'ES256', kid: keyId })}.${encode({ iss: teamId, iat: timestamp, exp: expiresAt })}`;
      // Workers accepts PEM here but rejects Node PrivateKeyObject in sign options.
      const signature = sign('sha256', Buffer.from(content), { key: privateKeyPem, dsaEncoding: 'ieee-p1363' });
      cached = `${content}.${signature.toString('base64url')}`;
    }
    return cached;
  };
}
