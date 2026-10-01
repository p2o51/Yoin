import { createTokenIssuer } from './issuer.mjs';
export { createTokenIssuer } from './issuer.mjs';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { pathToFileURL } from 'node:url';

export function createTokenServer(issueToken) {
  return createServer((request, response) => {
    response.setHeader('Content-Type', 'application/json');
    response.setHeader('Cache-Control', 'no-store');
    response.setHeader('X-Content-Type-Options', 'nosniff');
    if (request.method !== 'GET') {
      response.setHeader('Allow', 'GET');
      response.writeHead(405).end('{"error":"method_not_allowed"}');
    } else if (request.url === '/health') {
      response.end('{"ok":true}');
    } else if (request.url === '/token') {
      try { response.end(JSON.stringify({ developerToken: issueToken() })); }
      catch { response.writeHead(503).end('{"error":"token_unavailable"}'); }
    } else {
      response.writeHead(404).end('{"error":"not_found"}');
    }
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const issueToken = createTokenIssuer({
      privateKeyPem: readFileSync(process.env.APPLE_PRIVATE_KEY_PATH),
      keyId: process.env.APPLE_KEY_ID,
      teamId: process.env.APPLE_TEAM_ID,
    });
    issueToken(); // Fail before listening if the key cannot sign.
    const port = Number(process.env.PORT ?? 8788);
    if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Invalid PORT');
    const server = createTokenServer(issueToken);
    server.on('error', () => { console.error('Token service could not listen'); process.exitCode = 1; });
    server.listen(port, '127.0.0.1', () => console.log(`Token service listening on loopback port ${port}`));
    for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => server.close());
  } catch {
    console.error('Token service configuration failed. Check the key file, Team ID, Key ID and port.');
    process.exitCode = 1;
  }
}
