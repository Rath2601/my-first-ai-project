# my-first-ai-project

A learning project for going from **Java backend → AI application engineer**, built one phase at a time.

> Mental model: **an LLM is just a remote HTTP dependency that is stateless, non-deterministic, slow, and billed per token.** Every feature below is engineering around one of those adjectives — not magic.

**Stack:** Spring Boot 4.1 · Spring AI 2.0 · Java 25 · Google GenAI (Gemini 2.5 Flash/Pro)

### Endpoints

| Method & path | What it does | Key Phase‑1 ideas |
|---|---|---|
| `GET /` | Chat with memory; `?creative=true` loosens it | system prompt, memory, routing, token cap, resilience |
| `GET /chat/stream?q=` | Same answer, streamed token‑by‑token (SSE) | streaming / perceived latency |
| `POST /api/v1/tickets/triage` | Free‑text ticket → typed `TicketTriage` JSON | structured output, response cache |

### Run

```bash
export GEMINI_API_KEY="your-key"
./mvnw spring-boot:run
```

---

## Phase 1 — Foundational plumbing (stateless APIs & cost) ✅

Everything here is **implemented**. It turns a raw API call into a production‑shaped service.

### What we built & why

| # | Feature | Why it matters | Where |
|---|---|---|---|
| 1 | **System prompt / persona** | Highest‑authority instructions that set role & rules; users never control it | `prompts/system-persona.st` |
| 2 | **Sampling & cost controls** (`temperature`, `maxOutputTokens`, `model`, `topP`, `topK`, `stopSequences`) | Bound cost, latency, and randomness per request | `AIModelConfig` |
| 3 | **Structured output** (typed record, not prose) | Turns the model into a real system component you can code against | `TriageController`, `TicketTriage` |
| 4 | **Streaming (SSE)** | Improves **perceived** speed only — same tokens, same total time, **no cost saving** | `ChatStreamController` |
| 5 | **Token‑usage logging** | Cost telemetry. **Measures** usage; does **not** limit it | `ChatController` (usage metadata) |
| 6 | **Retry** (Spring AI) | Recover from transient `429`/`5xx`; never retries `400/401` | `application.properties` |
| 7 | **Chat memory** (sliding window) | Fakes statefulness by replaying last N msgs per `conversationId`. **Lost on restart, not shared across instances** | `ChatController` |
| 8 | **Routing by task** | Cheap model (Flash) for classification; Pro only when needed | `AIModelConfig` |
| 9 | **Pre‑flight token cap** (Gemini `countTokens`) | Reject oversized input (HTTP 413) *before* a billable call | `TokenGuardService` |
| 10 | **Response cache** (Caffeine) | Identical deterministic input skips the API call entirely | `TriageController` (`@Cacheable`) |
| 11 | **Timeout + circuit breaker** (Resilience4j) | Bound a hung call; fail fast during an outage instead of stacking timeouts | `ResilientChatService`, `ResilienceConfig` |
| 12 | **Prompt as a file** | Prompts are code — version & review them, not string literals | `prompts/system-persona.st` |
| — | *(bonus)* web‑search grounding | Lets the model use fresh info | `application.properties` |

### Cost levers (what actually moves the bill)

- **Output tokens cost ~3–5× input** → cap `maxOutputTokens`, and instruct brevity in the prompt (the cap only *truncates*).
- **Shrink input**: system prompt + history + context are re‑billed **every** call. A big system prompt is a tax on every request.
- **Route by task**; use the cheapest model that works.
- **Context caching** (cache a large stable prefix): high‑value **only once the prefix is big** (≳1–2K tokens). Our system prompt is ~326 tokens → **not worth caching yet**; revisit in Phase 2. Supported via `useCachedContent` / `autoCacheThreshold` / `GoogleGenAiCachedContentService`.
- **`stopSequences`**: minor savings; low priority.
- **Estimate before sending**: Gemini `countTokens` (✅ used) or the usage metadata — **not** JTokkit (that's OpenAI's tokenizer, wrong for Gemini).

### Core concepts (quick reference)

- **Tokens** — sub‑word chunks (~4 chars each). The unit of both **billing** and **capacity**.
- **Context window** — a hard budget: `input_tokens + output_tokens ≤ window`. Exceed it → `400`. Both the prompt and the reply share the same budget.
- **Temperature** — randomness dial. `0.0` = focused, repeatable (extraction/classification); higher (~0.8) = varied (brainstorming). **Even at 0 it's "consistent," not byte‑for‑byte guaranteed.**
- **Top‑P / Top‑K** — *which* candidate tokens are eligible each step (nucleus / top‑k), working alongside temperature. Usually leave near defaults; tune temperature first.
- **The four message roles** —
  - `system`: your rules/persona (highest authority; you own it).
  - `user`: the human input — **treat as untrusted**, like any request body.
  - `assistant`: the model's prior replies (replayed for continuity).
  - `tool`: results of function/tool calls (Phase 3).
- **Statelessness** — the API remembers nothing; "memory" is you re‑sending history each call, so a long chat re‑bills all prior turns (cost grows fast → keep a window).

### Good practices

- Secrets via env var (`${GEMINI_API_KEY}`), never committed.
- Prompts in files, not string literals.
- One config surface for model/sampling routing (`AIModelConfig`).
- **Measure ≠ limit**: log tokens *and* enforce a cap.
- **Retry ≠ timeout ≠ circuit breaker** — all three, they do different jobs.
- Cache only **deterministic, context‑free** calls (triage yes; the memory chat endpoint **no** — caching it would serve wrong/stale answers).
- Structured output and streaming **don't combine** (can't parse half a JSON object) — use `call()` for typed data, stream only free text.

---

## Phase 2 — Enterprise knowledge base (RAG) planned

Ground the model in **your** data so it stops confidently making things up.

- [ ] Embeddings + a vector store (**pgvector** in Postgres to start)
- [ ] Ingestion pipeline: read → **chunk** (~300–800 tokens, small overlap) → embed → store, with metadata (`source_id`, `tenant`, `version`)
- [ ] Retrieval + `QuestionAnswerAdvisor` ("answer only from this context"); refuse when retrieval is empty
- [ ] Answers with **citations** (chunk metadata)
- [ ] Keep vector store in sync with the source of truth (CDC / outbox → re‑embed changed docs only)
- [ ] **Now context caching earns its place** — the retrieved context/large prompt is the big stable prefix worth caching
- [ ] Hybrid search (keyword + vector) + a reranker; track recall@k on a golden set

**Concepts to note:** embeddings & cosine similarity, ANN search, chunking trade‑offs, the vector store as a *rebuildable projection* (never the source of truth), re‑embedding cost on model change.

---

## Phase 3 — Autonomous logic & orchestration (agents & tools) planned

Let the model *request* actions; **your code** decides and executes.

- [ ] `@Tool` methods — narrow, typed, server‑side‑validated (no raw SQL/HTTP on the write path)
- [ ] Workflow vs. agent — prefer explicit workflows; reserve agent loops for open‑ended tasks, always budgeted (max steps/tokens/time)
- [ ] AuthZ at the **tool boundary**, using the caller's identity (prompt is UX, not security)
- [ ] Human‑in‑the‑loop approval for destructive/financial actions
- [ ] Exactly‑once effects: outbox + idempotency key from business intent (`refund:{orderId}`)
- [ ] Immutable audit record per tool call; traces + cost alerts; red‑team evals in CI

**Concepts to note:** tool calling is a JSON protocol (the model never executes anything), **prompt injection** is the core threat (any read text can carry instructions), confused‑deputy problem, MCP for sharing tools across teams.

---
