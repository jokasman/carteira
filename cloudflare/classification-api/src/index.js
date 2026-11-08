const REQUEST_SCHEMA = "pt.carteira.classification-request";
const RESPONSE_SCHEMA = "pt.carteira.classification-response";
const SCHEMA_VERSION = 2;
const MAX_JSON_BYTES = 2 * 1024 * 1024;
const MAX_MOVEMENTS = 500;
const TASK_LIFETIME_MS = 30 * 24 * 60 * 60 * 1000;
const MAX_TASKS_PER_IP_PER_DAY = 10;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function jsonResponse(body, status, request, env) {
  const headers = new Headers({
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store, max-age=0",
    "X-Content-Type-Options": "nosniff"
  });
  const origin = request.headers.get("Origin");
  const currentOrigin = new URL(request.url).origin;
  const allowedOrigins = (env.ALLOWED_ORIGINS || "")
    .split(",")
    .map((value) => value.trim())
    .filter(Boolean);
  if (origin && (origin === currentOrigin || allowedOrigins.includes(origin))) {
    headers.set("Access-Control-Allow-Origin", origin);
    headers.set("Vary", "Origin");
  }
  return new Response(JSON.stringify(body), { status, headers });
}

function invalid(message) {
  const error = new Error(message);
  error.status = 400;
  return error;
}

function isObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function validId(value) {
  return Number.isSafeInteger(value) && value > 0;
}

function validAmount(value) {
  return Number.isFinite(value) && value >= 0.01 && Number.isSafeInteger(Math.round(value * 100));
}

function validDate(value) {
  return Number.isSafeInteger(value) && Number.isFinite(new Date(value).getTime());
}

function toCents(value) {
  return Math.round(value * 100);
}

function validateRequest(value) {
  if (!isObject(value) || value.schema !== REQUEST_SCHEMA || value.version !== SCHEMA_VERSION) {
    throw invalid("Este ficheiro não é um pedido Carteira compatível.");
  }
  if (typeof value.requestId !== "string" || !UUID_PATTERN.test(value.requestId)) {
    throw invalid("O identificador do pedido é inválido.");
  }
  if (!Number.isFinite(value.createdAt) || !Array.isArray(value.expenses) || value.expenses.length < 1 ||
      !Array.isArray(value.incomes) || value.expenses.length + value.incomes.length > MAX_MOVEMENTS) {
    throw invalid("O pedido não contém movimentos válidos.");
  }

  const movementIds = new Set();
  for (const item of value.expenses) {
    if (!isObject(item) || !validId(item.transactionId) || movementIds.has(item.transactionId)) {
      throw invalid("O pedido contém uma despesa inválida ou repetida.");
    }
    movementIds.add(item.transactionId);
    if (typeof item.displayTitle !== "string" || !item.displayTitle.trim() || item.displayTitle.length > 1000 ||
        !validDate(item.date) || !validAmount(item.amount) || typeof item.active !== "boolean") {
      throw invalid("O pedido contém dados de despesa inválidos.");
    }
    if (item.settlementIncomeId !== null && !validId(item.settlementIncomeId)) {
      throw invalid("O pedido contém uma liquidação inválida.");
    }
    if (item.settlementIncomeId === null && !item.active) {
      throw invalid("O estado de uma despesa é inválido.");
    }
    if (item.settlementIncomeId !== null && (item.active || !Array.isArray(item.budgets) || item.budgets.length > 0)) {
      throw invalid("O estado de uma despesa liquidada é inválido.");
    }
    if (!Array.isArray(item.budgets) || item.budgets.length > 100) {
      throw invalid("As opções de orçamento são inválidas.");
    }
    const budgetIds = new Set();
    for (const budget of item.budgets) {
      if (!isObject(budget) || !validId(budget.id) || budgetIds.has(budget.id) ||
          typeof budget.name !== "string" || !budget.name.trim() || budget.name.length > 500) {
        throw invalid("O pedido contém opções de orçamento inválidas.");
      }
      budgetIds.add(budget.id);
    }
  }

  for (const income of value.incomes) {
    if (!isObject(income) || !validId(income.transactionId) || movementIds.has(income.transactionId)) {
      throw invalid("O pedido contém uma receita inválida ou repetida.");
    }
    movementIds.add(income.transactionId);
    if (typeof income.displayTitle !== "string" || !income.displayTitle.trim() || income.displayTitle.length > 1000 ||
        !validDate(income.date) || !validAmount(income.amount) || typeof income.active !== "boolean" ||
        !Array.isArray(income.previousExpenseIds) || income.previousExpenseIds.some((id) => !validId(id)) ||
        new Set(income.previousExpenseIds).size !== income.previousExpenseIds.length) {
      throw invalid("O pedido contém dados de receita inválidos.");
    }
    if (income.active !== (income.previousExpenseIds.length === 0)) {
      throw invalid("O estado de uma receita é inválido.");
    }
  }

  const incomeById = new Map(value.incomes.map((income) => [income.transactionId, income]));
  for (const expense of value.expenses) {
    if (expense.settlementIncomeId !== null) {
      const income = incomeById.get(expense.settlementIncomeId);
      if (!income || !income.previousExpenseIds.includes(expense.transactionId)) {
        throw invalid("A associação de uma despesa liquidada é inválida.");
      }
    }
  }
  for (const income of value.incomes) {
    const linkedIds = value.expenses
      .filter((expense) => expense.settlementIncomeId === income.transactionId)
      .map((expense) => expense.transactionId);
    if (linkedIds.length !== income.previousExpenseIds.length ||
        linkedIds.some((id) => !income.previousExpenseIds.includes(id))) {
      throw invalid("As despesas liquidadas não correspondem à receita do pedido.");
    }
  }
}

function validateResponse(value, requestValue) {
  if (!isObject(value) || value.schema !== RESPONSE_SCHEMA || value.version !== SCHEMA_VERSION ||
      value.requestId !== requestValue.requestId) {
    throw invalid("A resposta não corresponde ao pedido Carteira.");
  }
  const splits = value.expenseSplits || [];
  if (!Array.isArray(value.assignments) || !Array.isArray(value.settlements) || !Array.isArray(splits) ||
      value.assignments.length + value.settlements.length + splits.length < 1 ||
      value.assignments.length + value.settlements.length + splits.length > MAX_MOVEMENTS) {
    throw invalid("A resposta não contém decisões válidas.");
  }

  const expensesById = new Map(requestValue.expenses.map((expense) => [expense.transactionId, expense]));
  const incomesById = new Map(requestValue.incomes.map((income) => [income.transactionId, income]));
  const decisionExpenseIds = new Set();
  const assignmentsById = new Map();
  for (const assignment of value.assignments) {
    if (!isObject(assignment) || !validId(assignment.transactionId) || !validId(assignment.budgetId) ||
        decisionExpenseIds.has(assignment.transactionId)) {
      throw invalid("A resposta contém uma classificação inválida ou repetida.");
    }
    const expense = expensesById.get(assignment.transactionId);
    if (!expense || expense.settlementIncomeId !== null || !expense.budgets.some((budget) => budget.id === assignment.budgetId)) {
      throw invalid("A resposta escolhe um orçamento que não consta no pedido.");
    }
    decisionExpenseIds.add(assignment.transactionId);
    assignmentsById.set(assignment.transactionId, assignment);
  }

  const splitsById = new Map();
  const expectedBudgetsById = new Map();
  for (const split of splits) {
    const expense = isObject(split) ? expensesById.get(split.transactionId) : null;
    if (!expense || splitsById.has(split.transactionId) || expense.settlementIncomeId !== null || !expense.active ||
        !Array.isArray(split.parts) || split.parts.length !== 2) {
      throw invalid("A resposta contém uma divisão de despesa inválida.");
    }
    let totalCents = 0;
    let firstBudgetId = null;
    for (const part of split.parts) {
      if (!isObject(part) || typeof part.title !== "string" || !part.title.trim() || part.title.trim().length > 120 ||
          !validAmount(part.amount) || (part.budgetId !== null && !validId(part.budgetId))) {
        throw invalid("Uma parcela da despesa está inválida.");
      }
      const cents = toCents(part.amount);
      if (Math.abs(part.amount * 100 - cents) > 0.000001) throw invalid("O valor de uma parcela tem de usar cêntimos.");
      if (part.budgetId !== null && !expense.budgets.some((budget) => budget.id === part.budgetId)) {
        throw invalid("A resposta escolhe um orçamento que não consta no pedido.");
      }
      if (firstBudgetId === null && part.budgetId !== null) firstBudgetId = part.budgetId;
      totalCents += cents;
    }
    if (totalCents !== toCents(expense.amount)) throw invalid("A soma das parcelas tem de igualar o valor original da despesa.");
    splitsById.set(split.transactionId, split);
    expectedBudgetsById.set(split.transactionId, firstBudgetId);
  }
  for (const [expenseId, budgetId] of expectedBudgetsById) {
    const assignment = assignmentsById.get(expenseId);
    if ((budgetId === null && assignment) || (budgetId !== null && assignment?.budgetId !== budgetId)) {
      throw invalid("A classificação da despesa dividida é inconsistente.");
    }
  }

  const incomeIds = new Set();
  const settledExpenseIds = new Set();
  const settledPartIds = new Set();
  for (const settlement of value.settlements) {
    const expenseIds = isObject(settlement) ? settlement.expenseIds || [] : [];
    const partRefs = isObject(settlement) ? settlement.expensePartRefs || [] : [];
    if (!isObject(settlement) || !validId(settlement.incomeId) || incomeIds.has(settlement.incomeId) ||
        !Array.isArray(expenseIds) || !Array.isArray(partRefs) || expenseIds.length + partRefs.length < 1 ||
        expenseIds.length + partRefs.length > MAX_MOVEMENTS) {
      throw invalid("A resposta contém uma liquidação inválida.");
    }
    const income = incomesById.get(settlement.incomeId);
    if (!income) throw invalid("A resposta contém uma receita que não consta no pedido.");
    incomeIds.add(settlement.incomeId);
    let totalCents = 0;
    const selectedIds = new Set();
    for (const expenseId of expenseIds) {
      if (!validId(expenseId) || selectedIds.has(expenseId) || decisionExpenseIds.has(expenseId)) {
        throw invalid("A resposta repete uma despesa ou movimento.");
      }
      const expense = expensesById.get(expenseId);
      if (!expense || (expense.settlementIncomeId !== null && expense.settlementIncomeId !== settlement.incomeId)) {
        throw invalid("A resposta escolhe uma despesa que não consta nesta liquidação.");
      }
      selectedIds.add(expenseId);
      decisionExpenseIds.add(expenseId);
      settledExpenseIds.add(expenseId);
      totalCents += toCents(expense.amount);
    }
    const selectedPartIds = new Set();
    for (const ref of partRefs) {
      if (!isObject(ref) || !validId(ref.transactionId) || !Number.isInteger(ref.partIndex) || ref.partIndex < 0 || ref.partIndex > 1) {
        throw invalid("A liquidação contém uma parcela inválida.");
      }
      const key = `${ref.transactionId}:${ref.partIndex}`;
      const split = splitsById.get(ref.transactionId);
      const part = split?.parts[ref.partIndex];
      if (!part || part.budgetId !== null || selectedIds.has(ref.transactionId) || selectedPartIds.has(key) || settledPartIds.has(key)) {
        throw invalid("A resposta repete uma parcela ou escolhe uma parcela indisponível.");
      }
      selectedPartIds.add(key);
      settledPartIds.add(key);
      totalCents += toCents(part.amount);
    }
    if (totalCents !== toCents(income.amount)) {
      throw invalid("As despesas da liquidação têm de igualar exatamente o valor da receita.");
    }
  }
  for (const expenseId of settledExpenseIds) {
    const split = splitsById.get(expenseId);
    if (split && (split.parts.some((part) => part.budgetId !== null) || settledPartIds.has(`${expenseId}:0`) || settledPartIds.has(`${expenseId}:1`))) {
      throw invalid("Uma divisão deve liquidar parcelas sem orçamento individualmente.");
    }
  }
}

async function readJson(request) {
  const length = Number(request.headers.get("Content-Length") || 0);
  if (length > MAX_JSON_BYTES) throw invalid("O pedido excede o limite de 2 MB.");
  let raw;
  try {
    raw = await request.text();
  } catch (_) {
    throw invalid("Não foi possível ler os dados enviados.");
  }
  if (new TextEncoder().encode(raw).byteLength > MAX_JSON_BYTES) throw invalid("O pedido excede o limite de 2 MB.");
  try {
    return JSON.parse(raw);
  } catch (_) {
    throw invalid("Os dados enviados não são JSON válido.");
  }
}

function base64UrlEncode(bytes) {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function base64UrlDecode(value) {
  const base64 = value.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - value.length % 4) % 4);
  const binary = atob(base64);
  return Uint8Array.from(binary, (character) => character.charCodeAt(0));
}

async function hashHex(value) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function hashToken(token) {
  if (typeof token !== "string" || token.length !== 43 || !/^[A-Za-z0-9_-]{43}$/.test(token)) {
    throw invalid("A ligação de classificação é inválida.");
  }
  try {
    if (base64UrlDecode(token).length !== 32) throw new Error("invalid token");
  } catch (_) {
    throw invalid("A ligação de classificação é inválida.");
  }
  return hashHex(token);
}

async function enforceCreateRateLimit(request, env, now) {
  const source = request.headers.get("CF-Connecting-IP") || "unknown";
  const dayNumber = Math.floor(now / 86400000);
  const sourceHash = await hashHex(`carteira-classification-rate:${dayNumber}:${source}`);
  await env.DB.prepare(
    `INSERT INTO creation_rate_limits (ip_hash, day_number, count)
     VALUES (?, ?, 1)
     ON CONFLICT (ip_hash, day_number) DO UPDATE SET count = count + 1`
  ).bind(sourceHash, dayNumber).run();
  const record = await env.DB.prepare(
    "SELECT count FROM creation_rate_limits WHERE ip_hash = ? AND day_number = ?"
  ).bind(sourceHash, dayNumber).first();
  if (record && record.count > MAX_TASKS_PER_IP_PER_DAY) {
    const error = new Error("Foi atingido o limite diário de pedidos. Tenta novamente amanhã.");
    error.status = 429;
    throw error;
  }
}

async function cleanupExpiredData(env, now) {
  await env.DB.batch([
    env.DB.prepare("DELETE FROM classification_tasks WHERE expires_at <= ?").bind(now),
    env.DB.prepare("DELETE FROM creation_rate_limits WHERE day_number < ?").bind(Math.floor(now / 86400000))
  ]);
}

async function createTask(request, env) {
  const body = await readJson(request);
  validateRequest(body.request);
  const requestJson = JSON.stringify(body.request);
  if (new TextEncoder().encode(requestJson).byteLength > MAX_JSON_BYTES) {
    throw invalid("O pedido excede o limite de 2 MB.");
  }

  const now = Date.now();
  await enforceCreateRateLimit(request, env, now);
  const tokenBytes = crypto.getRandomValues(new Uint8Array(32));
  const token = base64UrlEncode(tokenBytes);
  const tokenHash = await hashToken(token);
  const expiresAt = now + TASK_LIFETIME_MS;
  await env.DB.prepare(
    `INSERT INTO classification_tasks
     (token_hash, request_id, request_json, response_json, created_at, expires_at)
     VALUES (?, ?, ?, NULL, ?, ?)`
  ).bind(tokenHash, body.request.requestId, requestJson, now, expiresAt).run();
  return jsonResponse({ token, expiresAt }, 201, request, env);
}

async function loadTask(request, env) {
  const body = await readJson(request);
  const tokenHash = await hashToken(body.token);
  const now = Date.now();
  const task = await env.DB.prepare(
    `SELECT request_json, response_json, expires_at
     FROM classification_tasks WHERE token_hash = ?`
  ).bind(tokenHash).first();
  if (!task) return jsonResponse({ error: "Não foi encontrado um pedido para esta ligação." }, 404, request, env);
  if (task.expires_at <= now) {
    await env.DB.prepare("DELETE FROM classification_tasks WHERE token_hash = ?").bind(tokenHash).run();
    return jsonResponse({ error: "Esta ligação expirou. Pede ao remetente para criar um pedido novo." }, 410, request, env);
  }
  const requestValue = JSON.parse(task.request_json);
  return jsonResponse({
    request: requestValue,
    response: task.response_json ? JSON.parse(task.response_json) : null,
    expiresAt: task.expires_at
  }, 200, request, env);
}

async function submitTaskResponse(request, env) {
  const body = await readJson(request);
  const tokenHash = await hashToken(body.token);
  const now = Date.now();
  const task = await env.DB.prepare(
    `SELECT request_id, request_json, response_json, expires_at
     FROM classification_tasks WHERE token_hash = ?`
  ).bind(tokenHash).first();
  if (!task) return jsonResponse({ error: "Não foi encontrado um pedido para esta ligação." }, 404, request, env);
  if (task.expires_at <= now) {
    await env.DB.prepare("DELETE FROM classification_tasks WHERE token_hash = ?").bind(tokenHash).run();
    return jsonResponse({ error: "Esta ligação expirou. Pede ao remetente para criar um pedido novo." }, 410, request, env);
  }
  if (task.response_json) {
    return jsonResponse({ error: "Este pedido já foi concluído. Abre novamente a ligação para consultar a resposta." }, 409, request, env);
  }

  const requestValue = JSON.parse(task.request_json);
  validateResponse(body.response, requestValue);
  const responseJson = JSON.stringify(body.response);
  if (new TextEncoder().encode(responseJson).byteLength > MAX_JSON_BYTES) {
    throw invalid("A resposta excede o limite de 2 MB.");
  }
  const result = await env.DB.prepare(
    `UPDATE classification_tasks SET response_json = ?
     WHERE token_hash = ? AND response_json IS NULL AND expires_at > ?`
  ).bind(responseJson, tokenHash, now).run();
  if (result.meta.changes !== 1) {
    return jsonResponse({ error: "O pedido foi concluído entretanto. Abre novamente a ligação para consultar a resposta." }, 409, request, env);
  }
  return jsonResponse({ status: "complete" }, 200, request, env);
}

async function handleApi(request, env) {
  const url = new URL(request.url);
  if (request.method === "OPTIONS") {
    const origin = request.headers.get("Origin");
    const currentOrigin = url.origin;
    const allowedOrigins = (env.ALLOWED_ORIGINS || "").split(",").map((value) => value.trim());
    if (origin && origin !== currentOrigin && !allowedOrigins.includes(origin)) {
      return new Response(null, { status: 403, headers: { "Cache-Control": "no-store" } });
    }
    const headers = new Headers({
      "Access-Control-Allow-Methods": "POST, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type",
      "Access-Control-Max-Age": "86400",
      "Cache-Control": "no-store"
    });
    if (origin) {
      headers.set("Access-Control-Allow-Origin", origin);
      headers.set("Vary", "Origin");
    }
    return new Response(null, { status: 204, headers });
  }
  if (request.method !== "POST") {
    return jsonResponse({ error: "Método não permitido." }, 405, request, env);
  }
  if (!request.headers.get("Origin")) {
    return jsonResponse({ error: "A página de classificação não foi reconhecida." }, 403, request, env);
  }
  const origin = request.headers.get("Origin");
  const allowedOrigins = (env.ALLOWED_ORIGINS || "").split(",").map((value) => value.trim());
  if (origin !== url.origin && !allowedOrigins.includes(origin)) {
    return jsonResponse({ error: "A origem deste pedido não é permitida." }, 403, request, env);
  }

  try {
    if (url.pathname === "/api/create") return await createTask(request, env);
    if (url.pathname === "/api/load") return await loadTask(request, env);
    if (url.pathname === "/api/submit") return await submitTaskResponse(request, env);
    return jsonResponse({ error: "Operação desconhecida." }, 404, request, env);
  } catch (error) {
    const status = Number.isInteger(error.status) ? error.status : 500;
    const message = status === 500 ? "O serviço de classificação não conseguiu concluir o pedido. Tenta novamente." : error.message;
    return jsonResponse({ error: message }, status, request, env);
  }
}

export default {
  async fetch(request, env, context) {
    const response = await handleApi(request, env);
    context.waitUntil(cleanupExpiredData(env, Date.now()).catch(() => {}));
    return response;
  }
};
