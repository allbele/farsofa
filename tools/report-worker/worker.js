// SPDX-License-Identifier: AGPL-3.0-only

// Receives the error reports players choose to send from Farsofa (see app/.../core/Report.kt) and
// keeps them for ninety days. A Cloudflare Worker with one KV namespace bound as REPORTS.
//
// Nothing about the sender is stored: no address, no country, no headers. The report itself has
// already had addresses and identifiers struck out on the headset.
//
// Reading them:
//   npx wrangler kv key list --binding REPORTS
//   npx wrangler kv key get --binding REPORTS "2026-11-25/ABC123"

const MAX_BYTES = 400000;
const KEEP_SECONDS = 90 * 24 * 60 * 60;
const LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // none that are mistaken for one another when read aloud

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method !== "POST" || url.pathname !== "/farsofa") {
      return new Response("Not found", { status: 404 });
    }
    if (Number(request.headers.get("content-length") || 0) > MAX_BYTES) {
      return new Response("Too large", { status: 413 });
    }
    const body = await request.text();
    if (body.length > MAX_BYTES || !body.startsWith("Farsofa ")) {
      return new Response("Bad request", { status: 400 });
    }

    const random = crypto.getRandomValues(new Uint8Array(6));
    const code = Array.from(random, (value) => LETTERS[value % LETTERS.length]).join("");
    const day = new Date().toISOString().slice(0, 10);
    await env.REPORTS.put(`${day}/${code}`, body, { expirationTtl: KEEP_SECONDS });
    return new Response(code, { headers: { "content-type": "text/plain" } });
  },
};
