import { createTokenIssuer } from './issuer.mjs';

const reply = (body, status = 200, extra = {}) => Response.json(body, {
  status, headers: { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', ...extra },
});

export default {
  async fetch(request, env) {
    const path = new URL(request.url).pathname;
    if (request.method !== 'GET') return reply({ error: 'method_not_allowed' }, 405, { Allow: 'GET' });
    if (path === '/health') return reply({ ok: true });
    if (path !== '/token') return reply({ error: 'not_found' }, 404);
    try {
      // Coarse endpoint circuit breaker, not user authentication or a global quota.
      const { success } = await env.TOKEN_LIMITER.limit({ key: 'yoin-token-endpoint' });
      if (!success) return reply({ error: 'rate_limited' }, 429, { 'Retry-After': '60' });
      const issueToken = createTokenIssuer({
        privateKeyPem: env.APPLE_PRIVATE_KEY,
        keyId: env.APPLE_KEY_ID,
        teamId: env.APPLE_TEAM_ID,
      });
      return reply({ developerToken: issueToken() });
    } catch {
      return reply({ error: 'token_unavailable' }, 503);
    }
  },
};
