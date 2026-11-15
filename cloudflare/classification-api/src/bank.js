// Ligação ao banco através da Enable Banking (PSD2, só leitura).
// A chave privada fica nos segredos do Worker ou cifrada na D1; nunca é devolvida.
const EB_API = "https://api.enablebanking.com";
const CONSENT_DAYS = 180;

const enc = new TextEncoder();

function b64url(bytes) {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}
function b64ToBytes(b64) {
  const bin = atob(b64);
  return Uint8Array.from(bin, (c) => c.charCodeAt(0));
}
function bytesToB64(bytes) {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s);
}
async function sha256Hex(value) {
  const digest = await crypto.subtle.digest("SHA-256", enc.encode(value));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}
function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" }
  });
}
function html(body, status = 200) {
  return new Response(`<!doctype html><html lang="pt"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Carteira · Banco</title><style>body{font-family:system-ui,sans-serif;max-width:560px;margin:0 auto;padding:24px;line-height:1.5;color:#18352d;background:#f7f8f5}button,input{font:inherit}button{background:#2f6b4f;color:#fff;border:0;border-radius:12px;padding:12px 18px}.ok{color:#2f6b4f}.err{color:#b04a3a}</style></head><body>${body}</body></html>`, {
    status,
    headers: { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff", "X-Frame-Options": "DENY", "Referrer-Policy": "no-referrer" }
  });
}
function bad(message, status = 400) {
  const e = new Error(message);
  e.status = status;
  return e;
}
function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
}

// ---- chave privada ----
function derLength(n) {
  if (n < 0x80) return [n];
  const bytes = [];
  while (n > 0) { bytes.unshift(n & 0xff); n >>= 8; }
  return [0x80 | bytes.length, ...bytes];
}
function der(tag, content) {
  return Uint8Array.from([tag, ...derLength(content.length), ...content]);
}
function pkcs1ToPkcs8(pkcs1) {
  const version = Uint8Array.from([0x02, 0x01, 0x00]);
  const algorithm = Uint8Array.from([0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00]);
  const octet = der(0x04, pkcs1);
  return der(0x30, Uint8Array.from([...version, ...algorithm, ...octet]));
}
function pemToPkcs8(pem) {
  const text = String(pem || "").trim();
  const match = text.match(/-----BEGIN (RSA )?PRIVATE KEY-----([\s\S]+?)-----END (RSA )?PRIVATE KEY-----/);
  if (!match) throw bad("O ficheiro não contém uma chave privada válida.");
  const bytes = b64ToBytes(match[2].replace(/\s+/g, ""));
  return match[1] ? pkcs1ToPkcs8(bytes) : bytes;
}
async function importSigningKey(pem) {
  return crypto.subtle.importKey("pkcs8", pemToPkcs8(pem), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
}
async function storageKey(env) {
  if (!env.ENABLE_BANKING_PRIVATE_KEY) throw bad("O serviço do banco ainda não está configurado.", 503);
  const digest = await crypto.subtle.digest("SHA-256", enc.encode("carteira-bank-storage:" + env.ENABLE_BANKING_PRIVATE_KEY));
  return crypto.subtle.importKey("raw", digest, "AES-GCM", false, ["encrypt", "decrypt"]);
}
async function encryptText(env, text) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const data = await crypto.subtle.encrypt({ name: "AES-GCM", iv }, await storageKey(env), enc.encode(text));
  return bytesToB64(iv) + "." + bytesToB64(new Uint8Array(data));
}
async function decryptText(env, value) {
  const [iv, data] = value.split(".");
  const plain = await crypto.subtle.decrypt({ name: "AES-GCM", iv: b64ToBytes(iv) }, await storageKey(env), b64ToBytes(data));
  return new TextDecoder().decode(plain);
}
async function currentPem(env) {
  const row = await env.DB.prepare("SELECT value FROM bank_config WHERE name = 'private_key'").first();
  if (row) return decryptText(env, row.value);
  if (env.ENABLE_BANKING_PRIVATE_KEY) return env.ENABLE_BANKING_PRIVATE_KEY;
  throw bad("O serviço do banco ainda não está configurado.", 503);
}
async function makeJwt(env, pem) {
  if (!env.ENABLE_BANKING_APP_ID) throw bad("Falta o identificador da aplicação Enable Banking.", 503);
  const key = await importSigningKey(pem);
  const now = Math.floor(Date.now() / 1000);
  const header = b64url(enc.encode(JSON.stringify({ typ: "JWT", alg: "RS256", kid: env.ENABLE_BANKING_APP_ID })));
  const payload = b64url(enc.encode(JSON.stringify({ iss: "enablebanking.com", aud: "api.enablebanking.com", iat: now, exp: now + 3600 })));
  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, enc.encode(header + "." + payload));
  return header + "." + payload + "." + b64url(new Uint8Array(signature));
}
async function ebFetch(env, path, init = {}, pem = null) {
  const token = await makeJwt(env, pem || await currentPem(env));
  const response = await fetch(EB_API + path, {
    ...init,
    headers: { "Authorization": "Bearer " + token, "Content-Type": "application/json", ...(init.headers || {}) }
  });
  const text = await response.text();
  let body = null;
  try { body = text ? JSON.parse(text) : null; } catch (_) { body = { raw: text.slice(0, 300) }; }
  if (!response.ok) {
    const detail = body && (body.message || body.detail || body.error || body.raw) || response.statusText;
    const e = new Error("Enable Banking: " + String(detail).slice(0, 300));
    e.status = response.status >= 500 ? 502 : 400;
    throw e;
  }
  return body;
}

// ---- dispositivo ----
async function deviceHash(token) {
  if (typeof token !== "string" || !/^[A-Za-z0-9_-]{43}$/.test(token)) throw bad("Identificador do dispositivo inválido.");
  return sha256Hex("carteira-bank-device:" + token);
}
async function readBody(request) {
  const text = await request.text();
  if (text.length > 1024 * 1024) throw bad("Pedido demasiado grande.");
  try { return JSON.parse(text || "{}"); } catch (_) { throw bad("JSON inválido."); }
}

// ---- páginas ----
function setupPage(env) {
  return html(`<h1>Configurar ligação ao banco</h1>
<p>Escolhe o ficheiro <b>.pem</b> que a Enable Banking descarregou quando registaste a aplicação. A chave fica guardada de forma cifrada no serviço e não volta a ser mostrada.</p>
<p><input type="file" id="f" accept=".pem,.key,.txt,application/x-pem-file,text/plain"></p>
<p><button id="b">Enviar chave</button></p>
<p id="s"></p>
<script>
document.getElementById('b').onclick=async()=>{const s=document.getElementById('s');const f=document.getElementById('f').files[0];if(!f){s.textContent='Escolhe primeiro o ficheiro.';return}
s.textContent='A verificar…';try{const r=await fetch('/api/bank/key',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({pem:await f.text()})});const j=await r.json();
s.className=r.ok?'ok':'err';s.textContent=r.ok?'Chave aceite e verificada junto da Enable Banking. Podes fechar esta página.':(j.error||'Erro.')}catch(e){s.className='err';s.textContent='Não foi possível enviar.'}}
</script>`);
}
function callbackPage(ok, message) {
  return html(`<h1>${ok ? "Banco ligado" : "Ligação não concluída"}</h1><p class="${ok ? "ok" : "err"}">${escapeHtml(message)}</p><p>Volta à app Carteira e toca em <b>Sincronizar</b>.</p>`, ok ? 200 : 400);
}

// ---- operações ----
async function saveKey(request, env) {
  const body = await readBody(request);
  const pem = String(body.pem || "");
  pemToPkcs8(pem);
  await ebFetch(env, "/application", { method: "GET" }, pem);
  await env.DB.prepare("INSERT INTO bank_config(name, value) VALUES ('private_key', ?) ON CONFLICT(name) DO UPDATE SET value = excluded.value")
    .bind(await encryptText(env, pem)).run();
  return json({ ok: true });
}
async function status(env) {
  try {
    const app = await ebFetch(env, "/application", { method: "GET" });
    return json({ configured: true, active: app && app.active, name: app && app.name });
  } catch (e) {
    return json({ configured: false, error: e.message });
  }
}
async function listBanks(url, env) {
  const country = (url.searchParams.get("country") || "PT").toUpperCase().slice(0, 2);
  const data = await ebFetch(env, "/aspsps?country=" + encodeURIComponent(country) + "&psu_type=personal", { method: "GET" });
  const banks = (data && data.aspsps || []).map((a) => ({ name: a.name, country: a.country })).sort((a, b) => a.name.localeCompare(b.name));
  return json({ banks });
}
async function start(request, env) {
  const body = await readBody(request);
  const hash = await deviceHash(body.deviceToken);
  const aspsp = String(body.aspsp || "").trim();
  const country = String(body.country || "PT").toUpperCase().slice(0, 2);
  if (!aspsp || aspsp.length > 200) throw bad("Escolhe o banco.");
  const state = b64url(crypto.getRandomValues(new Uint8Array(24)));
  const validUntil = new Date(Date.now() + CONSENT_DAYS * 86400000).toISOString();
  const auth = await ebFetch(env, "/auth", {
    method: "POST",
    body: JSON.stringify({ access: { valid_until: validUntil }, aspsp: { name: aspsp, country }, state, redirect_url: env.BANK_REDIRECT_URL, psu_type: "personal" })
  });
  await env.DB.prepare("INSERT INTO bank_pending(state, device_hash, aspsp, created_at) VALUES (?, ?, ?, ?)").bind(state, hash, aspsp, Date.now()).run();
  return json({ url: auth.url });
}
async function callback(url, env) {
  const state = url.searchParams.get("state") || "";
  const code = url.searchParams.get("code");
  const error = url.searchParams.get("error_description") || url.searchParams.get("error");
  const pending = state ? await env.DB.prepare("SELECT device_hash, aspsp, created_at FROM bank_pending WHERE state = ?").bind(state).first() : null;
  if (!pending || Date.now() - pending.created_at > 3600000) return callbackPage(false, "Este pedido de ligação expirou ou não é reconhecido. Começa de novo na app.");
  await env.DB.prepare("DELETE FROM bank_pending WHERE state = ?").bind(state).run();
  if (!code) return callbackPage(false, "O banco não autorizou o acesso" + (error ? ": " + error : "."));
  const session = await ebFetch(env, "/sessions", { method: "POST", body: JSON.stringify({ code }) });
  const accounts = (session.accounts || []).map((a) => ({
    uid: a.uid,
    iban: a.account_id && a.account_id.iban || null,
    name: a.name || a.product || (a.account_id && a.account_id.iban) || "Conta",
    currency: a.currency || "EUR"
  })).filter((a) => a.uid);
  await env.DB.prepare("INSERT OR REPLACE INTO bank_sessions(session_id, device_hash, aspsp, accounts_json, valid_until, created_at) VALUES (?, ?, ?, ?, ?, ?)")
    .bind(session.session_id, pending.device_hash, pending.aspsp, JSON.stringify(accounts), session.access && session.access.valid_until || null, Date.now()).run();
  return callbackPage(true, `${pending.aspsp}: ${accounts.length} conta(s) autorizada(s).`);
}
async function deviceAccounts(hash, env) {
  const rows = await env.DB.prepare("SELECT session_id, aspsp, accounts_json, valid_until FROM bank_sessions WHERE device_hash = ? ORDER BY created_at DESC").bind(hash).all();
  const seen = new Set();
  const accounts = [];
  for (const row of rows.results || []) {
    for (const a of JSON.parse(row.accounts_json)) {
      if (seen.has(a.uid)) continue;
      seen.add(a.uid);
      accounts.push({ ...a, aspsp: row.aspsp, validUntil: row.valid_until });
    }
  }
  return accounts;
}
async function accounts(request, env) {
  const body = await readBody(request);
  return json({ accounts: await deviceAccounts(await deviceHash(body.deviceToken), env) });
}
function describe(t) {
  const remittance = Array.isArray(t.remittance_information) ? t.remittance_information.join(" ") : (t.remittance_information || "");
  const counterpart = (t.credit_debit_indicator === "CRDT" ? t.debtor && t.debtor.name : t.creditor && t.creditor.name) || "";
  return (remittance || counterpart || t.bank_transaction_code && t.bank_transaction_code.description || "Movimento").replace(/\s+/g, " ").trim().slice(0, 200);
}
async function transactions(request, env) {
  const body = await readBody(request);
  const hash = await deviceHash(body.deviceToken);
  const uid = String(body.accountUid || "");
  if (!(await deviceAccounts(hash, env)).some((a) => a.uid === uid)) throw bad("Esta conta não está ligada a este dispositivo.", 403);
  const today = new Date().toISOString().slice(0, 10);
  let dateFrom = /^\d{4}-\d{2}-\d{2}$/.test(body.dateFrom || "") ? body.dateFrom : new Date(Date.now() - 90 * 86400000).toISOString().slice(0, 10);
  // A Enable Banking recusa datas futuras (ex.: movimentos com data-valor no futuro).
  if (dateFrom > today) dateFrom = today;
  const out = [];
  let continuation = null;
  for (let page = 0; page < 30; page++) {
    const query = "?date_from=" + dateFrom + (continuation ? "&continuation_key=" + encodeURIComponent(continuation) : "");
    const data = await ebFetch(env, "/accounts/" + encodeURIComponent(uid) + "/transactions" + query, { method: "GET" });
    for (const t of data.transactions || []) {
      if (t.status && t.status !== "BOOK") continue;
      const amount = Number(t.transaction_amount && t.transaction_amount.amount);
      if (!Number.isFinite(amount) || amount === 0) continue;
      const date = t.booking_date || t.value_date || t.transaction_date;
      if (!date) continue;
      const signed = (t.credit_debit_indicator === "DBIT" ? -1 : 1) * Math.abs(amount);
      const description = describe(t);
      const ref = t.entry_reference || t.transaction_id || null;
      out.push({ id: ref, date, amount: signed, description });
    }
    continuation = data.continuation_key;
    if (!continuation) break;
  }
  return json({ transactions: out });
}

let schemaReady = false;
async function ensureSchema(env) {
  if (schemaReady) return;
  await env.DB.batch([
    env.DB.prepare("CREATE TABLE IF NOT EXISTS bank_config (name TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)"),
    env.DB.prepare("CREATE TABLE IF NOT EXISTS bank_pending (state TEXT PRIMARY KEY NOT NULL, device_hash TEXT NOT NULL, aspsp TEXT NOT NULL, created_at INTEGER NOT NULL)"),
    env.DB.prepare("CREATE TABLE IF NOT EXISTS bank_sessions (session_id TEXT PRIMARY KEY NOT NULL, device_hash TEXT NOT NULL, aspsp TEXT NOT NULL, accounts_json TEXT NOT NULL, valid_until TEXT, created_at INTEGER NOT NULL)"),
    env.DB.prepare("CREATE INDEX IF NOT EXISTS bank_sessions_device_idx ON bank_sessions (device_hash)")
  ]);
  schemaReady = true;
}

export async function handleBank(request, env, url) {
  try {
    await ensureSchema(env);
    if (url.pathname === "/banco/configurar" && request.method === "GET") return setupPage(env);
    if (url.pathname === "/banco/callback" && request.method === "GET") return await callback(url, env);
    if (url.pathname === "/api/bank/status" && request.method === "GET") return await status(env);
    if (url.pathname === "/api/bank/banks" && request.method === "GET") return await listBanks(url, env);
    if (request.method !== "POST") return json({ error: "Método não permitido." }, 405);
    if (url.pathname === "/api/bank/key") return await saveKey(request, env);
    if (url.pathname === "/api/bank/start") return await start(request, env);
    if (url.pathname === "/api/bank/accounts") return await accounts(request, env);
    if (url.pathname === "/api/bank/transactions") return await transactions(request, env);
    return json({ error: "Operação desconhecida." }, 404);
  } catch (error) {
    const status = Number.isInteger(error.status) ? error.status : 500;
    if (url.pathname.startsWith("/banco/")) return callbackPage(false, status === 500 ? "Ocorreu um erro inesperado." : error.message);
    return json({ error: status === 500 ? "O serviço do banco falhou. Tenta novamente." : error.message }, status);
  }
}
