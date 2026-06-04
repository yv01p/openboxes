# Modernizing a Grails Monolith — Class Deck (35–40 min cut)

> **Format:** slide outline + speaker notes. Each `## Slide N` is one slide: the bullets are what goes *on* the slide; the **Speaker notes** are what you *say*.
> **Audience:** architects & senior devs modernizing their own Grails app.
> **This is the short cut** of the 90-min deck (`2026-06-04-grails-migration-class-slides.md`). Same two braided narratives — (1) the migration engineering, (2) where an AI agent's mental model of Grails was wrong — and the same spine: *the artifact you're reading is not the source of truth.* Part I is compressed to a fast setup; Part II goes deep on the **3 "source of truth" findings** that form the through-line and folds the other 3 Grails traps into one slide.
> **Suggested timing:** ~35–40 min. Part I (history) ≈ 8 min; Part II (findings) ≈ 20 min; close + Q&A ≈ 7 min. 15 slides, ~2.5 min each.
> **Source corpus (all in this repo):** architecture review, parent migration design spec, teaching brief, `docs/process/` lessons, 8 retrospectives, 56 critical reviews.

---

## Slide 1 — Title

- **Modernizing a Grails Monolith**
- *Field notes from a strangler-fig migration to Spring Boot + React — the 35-minute cut*
- A real, fully-documented migration — and what an AI agent got wrong doing it
- One idea threads through everything: **the artifact you're reading is not the source of truth**

**Speaker notes.** "This is a compressed post-mortem of an actual, in-flight migration of a 160k-line Grails app — OpenBoxes, a real warehouse-management system — where every dead-end and recovery was written down as it happened. The twist: it was driven by an AI coding agent under tight human discipline, so we have an unusually honest log of *where the model's mental model of Grails was wrong* — which turns out to be exactly where any engineer goes wrong when they didn't write the original app. Hold onto one sentence for the next 35 minutes: the artifact you're reading — the domain class, the API contract, the phase plan — is not the source of truth. The running system is."

---

## Slide 2 — The patient, and how this was run

- **OpenBoxes** — open-source warehouse/inventory mgmt, in production for years
- Grails **3.3.16** (EOL) → ships Spring Boot **1.5** transitively (also EOL); **162** controllers · **150** services · **116** domain classes · **608** GSP views · ~**163k** lines Groovy
- **Two physical facts drive everything:** one shared schema, zero boundaries · a React SPA **welded inside** the monolith
- **Run as a spec-driven loop, every phase:** design → *adversarial* review → implement → retro — executed by an AI agent under a human stop-after-every-task gate
- **Why that matters:** it produced a candid *flight recorder* of where the model's Grails knowledge was wrong

**Speaker notes.** Anchor the scale — every number is measured, not estimated — then point at the two physical facts that the rest of the talk pays off: one shared schema with no boundaries is why "microservices" is hard; a React SPA welded inside the monolith is why the frontend cutover is hard. On the AI angle, be upfront that it's the *source of the honesty*, not a gimmick: a human team quietly fixes its own mistakes and never logs them, whereas here every wrong assumption was caught by a review or a build failure and written down with a root cause. Plant the thesis you'll pay off at the end: Grails isn't "too hard for AI" — it's structurally hard for *anyone* who didn't write it; the AI just hit the walls faster and logged every collision.

---

## Slide 3 — PART I: It started by attacking the brief

- The one-line brief: *"modernize Grails → Spring Boot microservices + React; some migration has started"*
- The adversarial review's job was to **falsify the brief** — and it did, **both halves**:
  - backend migration had **not started at all** (zero Java, zero Spring Boot services, no `pom.xml`)
  - the React SPA was **welded** to Grails (webpack emits GSP, auth via `JSESSIONID`, URLs via `window.CONTEXT_PATH`)
- **The 4 physical prerequisites of microservices the monolith violates:** shared data · no DTO/contract seam · in-process session auth · schema changelog pinned to the monolith's release version

**Speaker notes.** The first move in a modernization is to attack your *own premise*. The brief said "some migration has started" — the review proved both halves false: no backend Java at all, and the React frontend that *had* been built couldn't be lifted off Grails. The reframe to write on the wall: *backend migration has not started; the React frontend is the only modernization in progress, and it's a tenant of the Grails app.* The review's seven findings group cleanly: the first three are "the frontend isn't what you think," and the last four are these prerequisites — each a project in its own right that must be solved *before* the first extraction. Shared data is the big one: split it naively and you get a *distributed monolith*, strictly worse than where you started.

---

## Slide 4 — The decisions, and the roadmap

- **Philosophy: minimum viable infrastructure, deferred until the slice that needs it**
- **Strangler-fig + vertical slices** · **11** domain-aligned services · **shared MariaDB, per-service table ownership** · **JWT cookie** alongside `JSESSIONID` (no external OIDC) · **saga = outbox + HTTP relay, no broker** — built only in Phase 7
- **13 phases:** 0 foundations → **1–5 reference/leaf** (no saga) → **6 Inventory** → 7–11 coupled contexts → 12 delete Grails
- ⚠️ Written into the plan: *"Phase 3+ ordering is a recommendation"* — the most accurate sentence in the document

**Speaker notes.** Read the decisions as one coherent philosophy: the *smaller* option that still solves the real problem, deferred until demanded. Strangler-fig keeps it reversible at every step; vertical slices mean you never own a half-migrated screen; a transitional shared DB with per-service table ownership lets slice #1 actually ship instead of boiling the ocean on schema-per-service; a JWT cookie both sides understand means no Keycloak; the outbox-relay covers cross-service writes without operating Kafka, and it wasn't built until the first phase that needed it. The roadmap shape is deliberate — low-coupling reference data first, dense transactional contexts last. And note the humility clause: the plan anticipated its own fallibility. Hold that thought — it was right.

---

## Slide 5 — Where the roadmap stopped working (the hinge)

- **Phases 0–5 (reference/leaf): the template just worked** — extract entities, stand up service, route nginx, flip frontend. *This is the part every microservices talk shows you.*
- Then the coupling started, and two things broke the plan:
  - **Catalog:** the flat-DTO cutover **silently broke the live React contract** → an unplanned reconciliation phase *(Finding 4)*
  - **Inventory:** split into read-only Phase 6 + a deferred "Phase 6.5" — and **the split itself was wrong** *(the capstone)*
- From here on, **every phase had to be brainstormed individually** — you can't follow a stated order across coupling you never mapped
- **The richest lessons live here**, not in the clean stretch

**Speaker notes.** Give the clean stretch its due — it's the *achievable* part, and it's real. But it's exactly the part that lulls you: reference data is independent almost by definition, and the moment you reach a context with real runtime coupling the roadmap stops being a roadmap and becomes a hypothesis. Two breaks did it — Catalog (the marquee dead-end, Finding 4) and the Inventory mis-slice (the capstone). And here's why every later phase needed individual brainstorming: the early contexts were genuinely independent, so a template plus a stated order sufficed; the later ones are bound by *runtime* coupling — lifecycle hooks, derived-data refreshes, cross-service sagas — that the top-level "slice by data ownership" decomposition never captured.

---

## Slide 6 — PART II: The findings

- **3 spine findings** (the through-line) **+ 3 more Grails traps** (one fast slide)
- Each spine finding, four beats: **how we found it · what it is · what it implies · 🤖 what the AI got wrong**
- The spine: the **domain class** lies *(code)* → the **contract you assume** lies *(contract)* → the **phase plan** lies *(plan)*
- Same lesson, three altitudes

**Speaker notes.** Frame the structure so they can follow. Every spine finding gets the same four beats, and "how we found it" is deliberate because the *discovery mechanism is the transferable part* — students can install the same tripwires. The 🤖 beat is the second narrative: the specific wrong guess the model made and the discipline that caught it. And the spine connects three findings at three altitudes — code, contract, plan — same insight each time: trust the running system, verify everything written down.

---

## Slide 7 — Finding 1: the domain class is not the source of truth — the live DB is

- GORM constraints live in **code** (`nullable: false`, types, mappings); years of auto-migration **drift** the real schema away from them
- **`ddl-auto: validate` silently passes nullability drift** — it does *not* protect you
- **How we found it:** `ddl-auto: create` + `DESCRIBE` the live DB surfaced a cascade — a "non-null" `party` column that was actually **NULLABLE** (RC-1); **CHAR(38)** ids; **TINYINT** booleans; `@ElementCollection` column names wrong **5 of 5**; STI discriminator stored as a **fully-qualified class name**, not the simple name
- Worst: the column the plan relied on — **`inventory.warehouse_id` — does not exist**
- 🤖 **The AI trusted the domain class** (and, in Phase 6, a plan's cited evidence line) — exactly as a human reading the same files would
- **Habit:** `DESCRIBE` the table · `SHOW COLUMNS` · `SELECT DISTINCT class` for inheritance — *before* mapping anything

**Speaker notes.** The foundational finding; everything rests on it. The domain `.groovy` file *looks* like a schema definition — nullability, types, relationships — so a human or a model naturally trusts it. On a decade-old app that's false: the live schema has drifted, and the domain class is now a hopeful work of fiction. The cruel part is that `ddl-auto: validate`, the safety net you'd reach for, has a hole exactly where you need it — it does not flag nullability mismatches. The cure was to *not trust the file*: force divergence into the open with `ddl-auto: create` and `DESCRIBE` the live DB before mapping. That caught a whole cascade, capped by the worst case — a column the *plan* was built on that simply doesn't exist. The schema is ground truth; the domain class is a description of it, and hope is not a migration strategy.

---

## Slide 8 — Finding 4: the implicit API contract breaks the frontend cutover *(the marquee dead-end)*

- Grails `*ApiController`s render **nested association objects** and bind writes **by association name** (`product`, `supplier`)
- React was built against **those implicit shapes** — there was never a written contract
- We migrated to clean **flat DTOs** → the live React app **silently broke**. *"Correct" ≠ "compatible."*
- **Why it wasn't caught — three blind spots stacked:**
  - tests used **synthetic payloads** that matched the new DTO (green for the wrong reason)
  - the **dev DB was empty** — no test exercised a real write round-trip
  - the underlying **wrong-shape assumption** about the contract
- 🤖 **The AI assumed a cutover is a *wiring* task** ("point React at the new URL") — when it's a *verification* task

**Speaker notes.** The dramatic centerpiece — a real production-contract break with a documented root cause *and* recovery. Tell it as a story. The Grails API controllers serialize *nested* domain objects and accept writes keyed by association name; React was coded directly against those shapes, with no OpenAPI spec, no schema — just URL strings and whatever the controller happened to emit. When the new service returned clean flat DTOs, that output was *correct by every modern standard* and it **broke the running app**, because correct wasn't compatible. The gut-punch is *why it sailed through a green test suite*: synthetic test payloads that encoded the same wrong assumption, plus an empty DB that hid every write path. Three blind spots, each survivable alone, stacked into a silent break.

---

## Slide 9 — Finding 4, the cure: a cutover is a verification task

- **Capture the *real* payload** off the running app (browser network tab) — never trust a synthetic fixture for a contract
- **A cutover is a verification task, not a wiring task** — prove shape-compatibility, don't just re-point the URL
- **Seed the DB and do a real round-trip** — an empty DB hides every write path
- Best proof we found: **byte-identical diff** of the old Grails endpoint vs. the new service, **same seeded input**
- Codified as `docs/process/synthetic-payload-blind-spot.md` (RC-43 / 44 / 45)

**Speaker notes.** These three rules are the single most portable artifact in the class, so slow down. (1) The contract is *whatever the running system actually sends* — capture it from the live app; a fixture you wrote yourself just encodes your assumption and turns the test green for the wrong reason. (2) Reframe success: not "React calls the new URL" but "the new service is provably compatible with the old contract under real data." (3) Seed the database and exercise the full round-trip — the strongest technique was running the *same seeded input* through both the old endpoint and the new service and diffing for byte-identical output, which pins the migration to the original's *actual* behavior. If they take one slide home, this is the one.

---

## Slide 10 — Capstone: the *plan* can lie too (Phase 6.5)

- We sat down to **plan** the next inventory slice — and found the **inventory phase had been mis-sliced**. *No production code written.*
- **Two false load-bearing scope claims in one domain:**
  - `inventory.warehouse_id` exists *(it doesn't — Finding 1, one altitude up)*
  - "the bulk import does product create-or-find via HTTP" *(it doesn't — that endpoint **rejects** unknown products; create-or-find is a **different** endpoint, in catalog)*
- One wrong claim is bad luck; **two about one domain is a method problem** — the decomposition was done **above** the code, not **from** it
- The read/write split had cut **straight across a runtime coupling nobody named**

**Speaker notes.** The planning-level twin of Finding 1, and the freshest, richest case study — we never wrote a line of production code, we just tried to *plan* the next slice and discovered the boundary itself was wrong. Two of the phase's load-bearing scope claims were false the moment we read them against the code: the `warehouse_id` column again, and a backwards premise about the bulk import (the named endpoint *rejects* unknown products; create-or-find lives on a different endpoint that belongs to *catalog*). The diagnosis is the sentence to remember: one wrong claim is bad luck, but two about a single domain is a *method* problem — the decomposition was written above the code instead of from it. And the read/write split had run straight through a coupling nobody had named — which is the keystone on the next slide.

---

## Slide 11 — Capstone: decompose by coupling, sequence by demand

- **The keystone:** writing a `Transaction` fans out to a **ProductAvailability refresh** via **GORM lifecycle hooks** — the read/write split severed exactly this
- Its **nature dictates the mechanics:** *recompute-from-truth* (idempotent → a Grails/service dual-writer transition is safe **iff** the two computations are proven byte-identical) and **no process-independent trigger** (an out-of-process write gets **no** refresh — no safety net)
- **"Phase N.5 / the rest of X" is a deferral label, not a slice** — it hid *two* blocker classes (intra-service keystone vs. cross-service saga) under one comfortable name
- **Sequence by measured demand:** nginx routing × React's API surface × git churn — the roadmap's "next" phase (Ordering) was **dormant** (`purchaseOrder` untouched since 2024); the real demand (cycle count, stock movement) sat unmigrated
- 🤖 **The punchline:** empirical verification caught **both** mis-slices *before they shipped* — so the only fix is to pull verification **earlier**, to *phase-definition* time

**Speaker notes.** Four rules, each earned. (1) **Decompose by runtime coupling, not org-chart or data boundaries** — before slicing a domain, map what fires when its core entity is written, and make a slice own a *whole* coupling, never half. (2) The keystone's *nature* decides how it can move — you can't choose a migration mechanic until you've read those two properties. (3) **"The rest of X" is a smell** — it bundled an intra-service refresh keystone with a cross-service saga, two different blocker classes, under one name; when you see it, stop and re-decompose. (4) **Sequence by measured demand** — they computed live coupling empirically and found the roadmap's recommended next phase was dormant while the genuinely demanded work sat unmigrated. And the AI punchline is the punchline of the whole class: the discipline *worked* — it caught both mis-slices before they became bugs — so the remedy isn't a new technique, it's pulling the same verification earlier.

---

## Slide 12 — Three more Grails traps (in brief)

- **#2 — GORM is runtime magic:** you can't tell how a domain persists by *reading* it. Dynamic finders, **transient getters**, `belongsTo`/`hasMany` cascade, **`beforeInsert`/`beforeUpdate` hooks** (in **16** of our docs), cross-instance validators. *Ex: `ProductSupplier`'s transient pricing getters the new DTO had to replicate.* → Grep the tell-tales; ask the runtime.
- **#3 — GORM→JPA is translation, not transpile** — every entity needs judgment: bidirectional `belongsTo` → nullable `@ManyToOne`; audit hooks → `@EntityListeners` + `JwtAuditorAware`; cross-instance validator → service layer; `tablePerHierarchy false` → `@Inheritance(JOINED)` *(this one mapped cleanly)*. → Review each translation with a written rationale.
- **#5 — Grails 3.x pins a 2018 toolchain:** Gradle **4.10** → JDK **8** → Node **14**, cascading into CI. No clever fix: **isolate the old chain** (two Gradle wrappers, two JDKs); Grails stays on Java 8 until it's deleted.
- 🤖 **Common thread:** the AI kept defaulting to the *modern norm* — the current JPA construct, the current Gradle/plugin version — the **thin-corpus effect**

**Speaker notes.** Don't slow down here — these three are real and they matter, but they're the *work that follows* the spine, not the through-line. Hit each in a sentence. #2: GORM's behavior lives in conventions, not source — none of it is visible reading the class top to bottom, so a "map the fields" extraction silently drops behavior. #3: there's no automatic converter; each entity is a small reviewed design decision, and some translate cleanly while others need judgment. #5: the least glamorous finding and the one that quietly eats weeks — the only architecture that works is sealing the old toolchain in a box. The unifying 🤖 note: across all three, the model defaulted confidently to the modern norm because its recall of the *old* idioms is thinner — exactly the trap a human who only knows modern Spring would hit.

---

## Slide 13 — The through-line

- Finding 1: the **domain class** is not the source of truth *(code)*
- Finding 4: the **frontend contract you assume** is not the source of truth *(contract)*
- Capstone: the **phase plan** is not the source of truth *(plan)*
- **Same lesson, three altitudes: trust the running system; verify everything written down**
- 🤖 The honest reframe: Grails isn't "too hard for AI" — it's **structurally hard for anyone who didn't write it.** The thin AI corpus just made the hidden assumptions *visible* — and the verification disciplines are the cure either way

**Speaker notes.** Land the braid cleanly. Three of the findings are literally the same insight at different altitudes — code, contract, plan — and in each case the written artifact had quietly diverged from the running reality, and the only defense was to go check the reality. Then close the AI loop honestly: the lazy takeaway is "AI is bad at Grails," which is false and useless. The true story is that Grails' defining traits — convention-driven behavior in the runtime, schema that drifts from code, implicit unwritten contracts — make it structurally hard for *any* engineer who didn't write the app; the AI just collided with those walls faster and documented every collision. That documentation is the gift: nearly every discipline in this talk is a direct, specific response to a specific Grails characteristic.

---

## Slide 14 — Your day-one checklist

- **Schema, not domain class:** `DESCRIBE` every table; `SELECT DISTINCT class` for inheritance; run `ddl-auto: create` once to surface drift
- **Behavior, not source:** grep every domain for `beforeInsert`/`beforeUpdate`/`transient`/`hasMany`/`belongsTo`/validators
- **Contract, not assumption:** capture real payloads off the running SPA before you touch an endpoint
- **Round-trip, not green test:** seed the DB; diff old-vs-new output byte-for-byte
- **Coupling, not boundary:** map what fires when each core entity is written, *before* you slice
- **Demand, not roadmap:** rank contexts by routing × frontend calls × git churn

**Speaker notes.** End with something they can act on this week, before they plan anything. Each line maps to a finding: schema→1, behavior→2, contract & round-trip→4, coupling & demand→capstone. The two we compressed — per-entity translation judgment (#3) and toolchain isolation (#5) — are the work that *follows* once the checklist tells you what you're really dealing with. Offer the unabridged version: the full 90-min deck and lecture notes sit alongside this file, and the entire flight recorder is in the repo with file-and-line evidence.

---

## Slide 15 — Q&A / references

- Architecture review · parent design spec · 8 retrospectives · 56 adversarial reviews
- Process docs: `synthetic-payload-blind-spot.md`, `phase-decomposition-and-sequencing.md`, `sdd-reviewer-checklist.md`
- Full **90-min** deck + lecture notes alongside this file
- *Questions?*

**Speaker notes.** Four crisp answers to the questions you always get. **"Why not schema-per-service from day one?"** — that's a distributed-monolith-or-CDC project of its own; a transitional shared DB with per-service table ownership is the smaller move that still ships slice #1. **"Why no message broker?"** — the outbox-relay covers the consistency needs without operating Kafka; add the broker when scale demands it, not before. **"How much of this is AI-specific?"** — almost none of the *findings*; the AI surfaced them faster and logged them honestly. **"Could you have avoided the Phase 6.5 mis-slice?"** — yes, by applying the same empirical verification to *phase scope* that we already applied to specs; that's the one change we'd make, and the note to end on: pull your verification as early as it will go.
