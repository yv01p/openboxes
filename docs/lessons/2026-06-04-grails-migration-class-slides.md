# Modernizing a Grails Monolith — Class Deck

> **Format:** slide outline + speaker notes. Each `## Slide N` is one slide: the bullets are what goes *on* the slide; the **Speaker notes** are what you *say*.
> **Audience:** architects & senior devs modernizing their own Grails app.
> **Two narratives, deliberately braided:** (1) the migration engineering, and (2) the AI-assisted angle — this whole migration was executed by an AI agent under spec-driven discipline, and the retros candidly log where the AI guessed wrong about Grails. The second story is *why* the disciplines exist.
> **Source corpus (all in this repo):** `docs/reviews/2026-05-25-openboxes-architecture-review-1.md`, `docs/specs/2026-05-25-grails-to-spring-boot-migration-design.md`, `docs/lessons/2026-06-02_…-teaching-brief.md`, `docs/process/phase-decomposition-and-sequencing.md`, `docs/process/synthetic-payload-blind-spot.md`, 8 retrospectives, 56 critical reviews.
> **Suggested timing:** ~75–90 min. Part I (history) ≈ 25 min; Part II (findings) ≈ 50 min; close + Q&A ≈ 15 min.

---

## Slide 1 — Title

- **Modernizing a Grails Monolith**
- *Field notes from a strangler-fig migration to Spring Boot + React*
- A real migration, run end-to-end, fully documented
- Two stories at once: the migration, and what an AI agent got wrong doing it

**Speaker notes.** Set expectations in two sentences. "This isn't a theory talk about microservices. It's a post-mortem of an actual, in-flight migration of a 160k-line Grails app — OpenBoxes, a real warehouse-management system — where every decision, dead-end, and recovery was written down as it happened. And there's a twist: the migration was driven by an AI coding agent under tight human discipline, so we have an unusually honest record of *where the model's mental model of Grails was wrong* — which turns out to be the most useful teaching material in the whole project, because the AI's wrong guesses are the same ones any engineer makes when they didn't write the original app."

---

## Slide 2 — Agenda

- **Part I — The journey:** how a one-line brief became a 13-phase migration
- **Part II — The findings:** 5 Grails traps + 1 planning trap, each with *how we found it* and *what to do*
- A through-line: **the domain class lies, the plan lies, and verification is the cure**
- You leave with a **day-one checklist** for your own app

**Speaker notes.** "Part I is the story arc — arch review, slicing the monolith, the phases that went to plan, and the exact point where the plan stopped working. Part II is the payload: six substantive findings. For each one I'll tell you how it surfaced — usually painfully — what it actually is at a mid level, and the portable habit you can apply Monday morning. I'll close with a checklist you can run against your own Grails app on day one."

---

## Slide 3 — The patient

- **OpenBoxes** — open-source warehouse & inventory management, in production for years
- Grails **3.3.16** (EOL) → ships Spring Boot **1.5.22** transitively (also EOL)
- **162** controllers · **150** services · **116** domain classes · **608** GSP views
- ~**163k** lines of server Groovy + ~**77k** lines of GSP
- **One** shared MySQL/MariaDB schema, **zero** bounded-context boundaries
- A React SPA already wedged *inside* the monolith

**Speaker notes.** Anchor the scale — these numbers are from the architecture review, all measured, not estimated. The point is that this is *representative*: a decade-old Grails app, end-of-life framework, a single shared schema, and a half-finished React layer bolted on. If your app looks like this, good — the lessons transfer directly. Emphasize the last two bullets: one shared schema with no boundaries is the central physical fact that makes "microservices" hard, and a React frontend that's *welded* to Grails is the central frontend fact. Both come back as findings.

---

## Slide 4 — How this migration was actually run

- **Spec-driven loop, every phase:** design → *adversarial* review → implement → retrospective
- Executed by an **AI agent** (Claude Code) under a human stop-after-every-task gate
- Adversarial reviews were **subagents** told to *attack* the design/plan, not bless it
- Every phase ends in a retro with A–F triaged findings ("RC-1, RC-2…")
- **Why this matters for you:** it produced a candid log of *where the model's Grails knowledge was wrong*

**Speaker notes.** This is where you set up the second narrative explicitly. "I want to be upfront that this was AI-assisted, because it's not a gimmick — it's the source of the honesty. A human team tends to quietly fix its own mistakes and never write them down. Here, every wrong assumption got caught by a review or a build failure and *logged* with a root-cause. So we have ~56 adversarial review documents and 8 retrospectives that read like a flight recorder." Then plant the thesis you'll pay off at the end: "Here's the honest reframe — the AI struggled with Grails more than with Java, C#, or Python. Part of that is real: Grails 3 / GORM is a smaller, older training corpus, so the model's *recall of idioms* is thinner and it makes more plausible-but-wrong guesses about runtime behavior. But the bigger, truer story is that the difficulty is **structural to Grails itself**, and would bite any engineer who didn't write the original app. The verification habits we'll cover are the cure either way."

---

## Slide 5 — PART I: The Journey

- *How a one-line brief became a 13-phase migration*

**Speaker notes.** Section divider. "Before any code, two things had to happen: an honest assessment of where we actually were, and a set of irreversible decisions. Both are where most real migrations go wrong — they skip straight to extracting a service."

---

## Slide 6 — It started with an adversarial architecture review

- The brief: *"modernize away from Grails → Spring Boot microservices + React frontend; some migration has started"*
- The review's job: **attack the brief**, not validate it
- Gut-punch finding: **the backend migration had not started at all** — zero Java, zero Spring Boot services, zero `pom.xml`
- The React SPA was **structurally welded** to Grails (webpack emits GSP, auth via `JSESSIONID`, URLs via `window.CONTEXT_PATH`)
- 7 "literal-wrongness" findings against the brief's assumptions

**Speaker notes.** The lesson here is *start with an adversarial review of your own premise*. The brief said "some migration work has started" — the review proved both halves of the premise false: there was no backend Java at all, and the React frontend that *had* been built couldn't be lifted off Grails (its build literally writes Groovy Server Pages into the Grails view tree, and it authenticates with the Grails session cookie). If you'd believed the brief, you'd have started extracting service #1 on top of a frontend that can't talk to it. The review reframed the mental model: "backend migration has not started; the React frontend is the only modernization in progress, and it's a *tenant* of the Grails app." Tell your students: write down your assumptions and have someone (or something) try to falsify them *before* you plan.

---

## Slide 7 — Seven things the brief got wrong

1. No backend migration started; React embedded in the monolith
2. React not independently deployable (welded to GSP + session + `CONTEXT_PATH`)
3. ~¾ of the UI is still GSP (608 views) — not "a React frontend"
4. **116 domain classes, one shared schema, no boundaries — the data won't split**
5. **No DTO/contract seam** between domain model and HTTP — every cut breaks callers
6. Auth is a Grails session cookie + one `SecurityInterceptor.matchAll()` — doesn't cross a service boundary
7. Liquibase changelog tied to the monolith's release version — no concurrent schema evolution

**Speaker notes.** Don't read all seven verbatim — group them. Findings 1–3 are "the frontend isn't what you think." Findings 4–7 are the four *physical prerequisites* of microservices that the monolith violates: shared data (4), no contracts (5), in-process auth (6), single-owner schema migrations (7). The teachable point: "microservices" is a plural, load-bearing word. Each of these four is a project in its own right that has to be solved *before* the first extraction, not during it. Finding 4 is the big one — splitting a shared schema naively gives you a *distributed monolith* (many services hitting one DB), which is strictly worse than where you started. Park that; it drives the data-ownership decision on the next slide.

---

## Slide 8 — Four forced decisions, made before any code

- **Strategy:** strangler-fig vs. parallel rewrite vs. greenfield-hybrid
- **Sequencing:** frontend-first vs. backend-first vs. **vertical slices**
- **Data ownership:** private schema-per-service vs. shared DB vs. event replication
- **Auth across the coexistence window:** OIDC vs. gateway-translation vs. shared session
- *None of these is cheaply reversible — so they're decided up front, explicitly*

**Speaker notes.** The architecture review's second half identified four decisions it refused to make *for* the team, because each one's consequences span the entire multi-year migration. This is a transferable move: separate "facts about the code" (the 7 findings) from "irreversible choices" (these 4). Surface the choices to the humans; don't let them be made by accident. The review's own recommendation was a red light: "🛑 surface forced decisions to the user" — i.e., stop and choose deliberately. Tell students: every migration has its own version of these four. Name them before you write a line of code.

---

## Slide 9 — The decisions we actually made

- **Strangler-fig + vertical slices** — each phase removes Grails code as it adds Spring Boot
- **11 domain-aligned services**, boundaries by bounded context (not by Grails package layout)
- **Shared MariaDB, per-service table ownership** — cross-service reads start as direct JDBC, flip to HTTP when the owner exists
- **JWT (`obx_token`, HS256, HttpOnly cookie)** alongside `JSESSIONID`; issued by Grails first, then by identity-service. No external OIDC
- **Saga = transactional outbox + HTTP relay + idempotent subscribers.** No message broker. Built only when first needed (Phase 7)

**Speaker notes.** Walk these as a coherent philosophy: *minimum viable infrastructure, deferred until demanded.* Strangler-fig means it's reversible at every step. Vertical slices mean each phase ships a whole context's backend *and* frontend, so you're never stuck with a half-migrated screen. The data decision is the pragmatic one — they deliberately accepted a *transitional* shared DB with per-service table ownership rather than boiling the ocean on schema-per-service or CDC up front; cross-service reads are direct JDBC until the owning service exists, then become HTTP calls. Auth: no Keycloak, just a JWT cookie the Grails app and the services both understand, dual-mode during transition. Saga: no Kafka — a database outbox table polled by a relay, built in Phase 7 because that's the first phase that needs cross-service writes. The meta-lesson for architects: **don't pay for infrastructure before the slice that needs it.** Every one of these is the *smaller* option that still solves the actual problem.

---

## Slide 10 — The roadmap: 13 phases

- **Phase 0:** foundations (nginx routing, JWT, Playwright e2e harness)
- **1–5:** Document → Identity → Location → Organization → Catalog *(reference/leaf contexts; no saga)*
- **6:** Inventory *(the largest single extraction)*
- **7–11:** Ordering (+saga) → Shipping → Requisition → Billing → Reporting
- **12:** delete Grails
- ⚠️ Written into the plan: *"Phase 3+ ordering is a recommendation"* — the order would be revisited

**Speaker notes.** Two things to highlight. First, the *shape*: the early phases are reference data and leaf contexts — Document, Identity, Location, Organization, Catalog — things lots of other contexts read but that don't themselves write across boundaries. Saga involvement is literally "none" until Phase 7. That's not an accident; you extract the low-coupling stuff first to build muscle and de-risk. Second — and this is the part that matters for Part II — the plan itself contained a humility clause: phase ordering from 3 onward was explicitly "a recommendation," to be re-decided once the team had real slice experience. **Hold that thought.** The plan anticipated its own fallibility, and it was right to.

---

## Slide 11 — The clean stretch: phases 0–5 mostly went to plan

- Reference/leaf contexts: **clean data ownership, no cross-service writes**
- The per-slice template just *worked* — extract entities, stand up service, route nginx, flip frontend
- Each phase: design → adversarial review → implement → retro → tag (`phase-N-*`)
- Cleanup/codify mini-phases (4.1, 5.1, 5.2) folded lessons back into process docs
- This is the part everyone's microservices talk shows you

**Speaker notes.** Give the plan its due — the strangler-fig template genuinely worked for the first several slices, and that's worth showing because it's the *achievable* part. Identity, Location, Organization, Catalog all came up as independent Spring Boot services behind nginx, with Grails code deleted as each landed. The discipline even folded its own lessons back in: after a phase, small "cleanup" and "codify" phases (4.1, 5.1, 5.2) hardened shared infrastructure (e.g., extracting a shared JWT library, adding a CI lint gate) so the next phase didn't re-learn it. **But** — and this is your pivot — this clean stretch is exactly the part that lulls you. The moment you reach contexts with real runtime coupling, the roadmap stops being a roadmap.

---

## Slide 12 — Where the roadmap stopped working

- **Catalog (Phase 5.5):** the flat-DTO cutover *silently broke the live React contract* → an unplanned reconciliation phase
- **Inventory (Phase 6):** had to split into a read-only Phase 6 + a deferred "Phase 6.5" — which, when we sat down to plan it, **revealed the inventory phase had been mis-sliced**
- The "XX": **dense runtime coupling + demand that diverged from the roadmap**
- From here on, **every phase had to be brainstormed individually** — you can't follow a stated order across coupling you never mapped
- The richest lessons live *here*, not in the clean stretch

**Speaker notes.** This is the hinge of Part I. Two things broke the roadmap. (1) Catalog: when we cut over to clean flat DTOs, we *silently broke the running React app*, because React had been built against Grails' implicit nested-object payloads — that's Finding 4, the marquee dead-end, and it forced a whole unplanned "write-contract reconciliation" phase. (2) Inventory: it was too big and coupled to do in one go, so it got split — a safe read-only Phase 6, with everything else deferred to "Phase 6.5." But when we actually sat down to plan 6.5, we discovered the *split itself* was wrong — that's the capstone finding. The generalization, and the answer to "why did the later phases need individual brainstorming": the early contexts were genuinely independent, so a template + a stated order sufficed. The later contexts are bound together by runtime coupling — lifecycle hooks, derived-data refreshes, cross-service sagas — that the top-level "slice by data ownership" decomposition never captured. You cannot follow a roadmap across coupling you haven't mapped. So each remaining phase became its own brainstorming exercise: map the coupling, measure the demand, *then* decide the slice.

---

## Slide 13 — PART II: The Findings

- 5 Grails traps + 1 planning trap
- For each: **how we found it · what it is · what it implies · 🤖 what the AI got wrong**
- The unifying thesis: *the artifact you're reading is not the source of truth*

**Speaker notes.** Frame the structure so they can follow along: every finding gets the same four beats. The "how we found it" beat is deliberately included because *the discovery mechanism is the transferable part* — students can install the same tripwires. The 🤖 beat is the second narrative: for each finding I'll show you the specific wrong guess the AI made and the discipline that caught it. And here's the spine that connects all six: in Finding 1 the *domain class* isn't the source of truth; in Finding 4 the *frontend contract you assume* isn't; in the capstone the *phase plan* isn't. Same lesson at three altitudes — **trust the running system, verify everything written down.**

---

## Slide 14 — Finding 1: The domain class is not the source of truth — the live DB is

- GORM constraints live in **code** (`nullable: false`, types, mappings); years of auto-migration **drift** the real schema away from them
- **`ddl-auto: validate` silently passes nullability drift** — it does *not* protect you
- How we found it: switching to `ddl-auto: create` surfaced a `party` column that was **NULLABLE** while the domain said `nullable: false` (Phase 4, RC-1)
- More of the same: **CHAR(38)** ids & **TINYINT** booleans (Phase 5); `@ElementCollection` column names wrong **5 of 5**; `@DiscriminatorValue` stored as a **fully-qualified class name**, not the simple name
- Worst case: a column the plan relied on — **`inventory.warehouse_id` — did not exist at all** (Phase 6)

**Speaker notes.** This is the foundational finding; spend time here. In Grails, the `.groovy` domain class *looks* like a schema definition — it has nullability, types, relationships. But on a decade-old app, the live schema has drifted from it through years of migrations, and the domain class now *lies*. The cruel part: Hibernate's `ddl-auto: validate`, which you'd expect to catch this, does **not** flag nullability mismatches — it passes happily. The discovery mechanism was using `ddl-auto: create` against a scratch DB to force-surface divergence, plus literally running `DESCRIBE <table>` against the live database before mapping anything. That caught a cascade: a column the domain swore was non-null was nullable; ids were `CHAR(38)` not the assumed type; booleans were `TINYINT`; an `@ElementCollection`'s inner column names were wrong in all five cases; and the Grails single-table-inheritance discriminator stores the *fully-qualified* class name (`org.pih.warehouse...`) where JPA defaults to the simple name — an instant silent mismatch. The worst was Phase 6: the plan was built on `inventory.warehouse_id`, a column that **does not exist** — the real facility link is `location.inventory_id`. **🤖 What the AI got wrong:** it trusted the domain class, and in Phase 6 it trusted a plan's cited evidence line, exactly as a human reading the same files would. **The habit:** before migrating any GORM domain — `DESCRIBE` the table, `SHOW COLUMNS`, and `SELECT DISTINCT class FROM <table>` for inheritance. The schema is ground truth; the domain class is a hopeful description of it.

---

## Slide 15 — Finding 2: GORM is runtime magic — static reading under-determines behavior

- You **cannot tell how a domain persists by reading it**
- Hidden behavior: dynamic finders, **transient getters**, `belongsTo`/`hasMany` **cascade**, **lifecycle hooks** (`beforeInsert`/`beforeUpdate`), cross-instance **validators**, `cache true`
- Lifecycle hooks alone appear in **16** of our docs — they fire on every write, invisibly
- How we found it: a JPA entity that read "complete" was missing behavior that only existed at runtime (e.g., `ProductSupplier`'s **transient pricing getters**, which the new DTO had to replicate)
- Convention-over-configuration means the *conventions* carry the behavior, not the source

**Speaker notes.** Finding 1 was "the schema isn't in the file." Finding 2 is "the *behavior* isn't in the file." GORM is convention-driven and runtime-woven: a domain class gets dynamic finders it never declares, getters marked transient that compute values on read, cascade rules implied by `belongsTo`/`hasMany`, audit fields stamped by lifecycle hooks on every insert/update, and validators that can reach across instances. None of this is visible by reading the class top-to-bottom. Concrete example: `ProductSupplier` has transient getters that compute pricing — there's no column, so a naive "map the fields" extraction drops the behavior entirely; the new service's DTO had to *replicate* the computation. **🤖 What the AI got wrong:** this is the heart of the "thin corpus" effect. The model is excellent at reading Groovy syntax — that was never the bottleneck — but its *recall of GORM's runtime conventions* is thinner than for, say, Spring, so it under-predicts what a domain class does at runtime and produces plausible-but-incomplete mappings. A human who didn't write the app makes the identical error. **The habit:** before trusting a domain, grep it for `beforeInsert`, `beforeUpdate`, `beforeDelete`, `transient`, `hasMany`, `belongsTo`, custom validators, and `mapping`/`cache` blocks — and trace what actually fires on a write.

---

## Slide 16 — Finding 3: GORM→JPA is a translation with real impedance mismatches

- It is **not** a mechanical transpile — every entity needs judgment
- `belongsTo [a, b]` bidirectional → **no JPA 1:1 equivalent**; mapped as nullable `@ManyToOne`
- `beforeInsert`/`beforeUpdate` audit stamping → **`@EntityListeners` + `AuditorAware`** (a `JwtAuditorAware` that reads the user from the token)
- GORM cross-instance validator → **service-layer validation**
- `tablePerHierarchy false` → **`@Inheritance(JOINED)`** (Person ← User — this one mapped cleanly)
- Strangler bridge: `Document.load(id)` → `entityManager.getReference()`

**Speaker notes.** This is the "now translate it" finding, and the message is that there's no automatic GORM→JPA converter that will save you — each entity is a small design decision. Give two or three concrete impedance mismatches: GORM's bidirectional `belongsTo` has no clean JPA 1:1 analog, so it becomes a nullable `@ManyToOne` with a judgment call about ownership. GORM's lifecycle audit hooks (who created/modified this, when) become a JPA `@EntityListeners` plus an `AuditorAware` implementation — and since auth is now a JWT, that's a `JwtAuditorAware` that pulls the user from the token. GORM validators that compare across instances can't live on the entity anymore; they move to the service layer. Show one that *worked cleanly* too — `tablePerHierarchy false` maps straight to JPA `@Inheritance(JOINED)`, and Person←User came over without drama — so it's not all pain; it's *judgment*, and some of the judgments are easy. **🤖 What the AI got wrong:** the model tends to reach for the syntactically-closest JPA construct (a 1:1, a simple `@Column`) rather than the *behaviorally* correct one, because it pattern-matches on shape. The fix is per-entity human review of each translation — which is exactly what the adversarial design reviews caught. **The habit:** treat each entity translation as a reviewed decision with a rationale, not a find-and-replace.

---

## Slide 17 — Finding 4: The implicit API contract breaks the frontend cutover *(the marquee dead-end)*

- Grails `*ApiController`s render **nested association objects** and bind payloads **by association name** (`product`, `supplier`)
- React was built against **those implicit shapes** — there was never a written contract
- We migrated to clean **flat DTOs** → the live React app **silently broke**
- It passed every test, because the tests used **synthetic payloads**, not real SPA payloads
- And an **empty dev DB hid every write path** — nothing exercised the round-trip

**Speaker notes.** This is the dramatic centerpiece — a real production-contract break, with a documented root cause and recovery. Tell it as a story. The Grails API controllers don't return flat JSON; they serialize *nested* domain objects, and they accept writes keyed by *association name*. React was coded directly against those shapes — there was no OpenAPI spec, no schema, just URL strings and whatever the controller happened to emit. When the new service returned clean, flat, well-designed DTOs, it was *correct by every modern standard* and it **broke the running app**, because "correct" wasn't "compatible." The gut-punch is *why it wasn't caught*: the migration tests used synthetic, hand-written payloads that matched the new DTO — so they were green — while the real SPA was sending and expecting the old nested shape. And the dev database was empty, so no test ever exercised a real write round-trip. Three separate blind spots stacked: wrong-shape assumption, synthetic test data, empty DB. The recovery was a dedicated "write-contract reconciliation" phase that captured the *real* payloads and reconciled them. **🤖 What the AI got wrong:** it assumed a cutover is a *wiring* task ("point React at the new endpoint") when it's actually a *verification* task ("prove the new endpoint emits and accepts byte-compatible shapes against real data"). **The three habits** (next slide).

---

## Slide 18 — Finding 4, the cure: a cutover is a verification task

- **Capture the *real* payload** off the running app — never trust a synthetic fixture for a contract
- **A cutover is a verification task, not a wiring task** — prove shape-compatibility, don't just re-point the URL
- **Seed the DB and do a real round-trip** — an empty DB hides every write path
- Best proof we found: **byte-identical diff** of old Grails endpoint vs. new service output, same seeded input
- Codified as `docs/process/synthetic-payload-blind-spot.md` (RC-43 / 44 / 45)

**Speaker notes.** These three rules are the most directly portable artifact in the whole class, so slow down. (1) The contract is *whatever the running system actually sends* — go capture it from the live app or the browser network tab; a fixture you wrote yourself just encodes your assumption and turns the test green for the wrong reason. (2) Reframe the cutover: success is not "React now calls the new URL," it's "the new service is provably compatible with the old contract under real data." (3) Seed the database and exercise the full write round-trip — the empty-DB trap is insidious because everything *looks* tested. The strongest technique they landed on: run the same seeded input through both the old Grails endpoint and the new service and diff the outputs for byte-identical equality — that pins the migration to the original's *actual* behavior, not to a hand-written expectation. All three are written up in the repo as a process doc so the next phase can't repeat them. Tell students: if you take one slide home, take this one.

---

## Slide 19 — Finding 5: Grails 3.x pins a 2018 toolchain, and it cascades

- Grails **3.3.x** → Gradle **4.10.3** → **JDK 8** → Node **14** — a chain of frozen versions
- Every modern tool fights the old runtime: **Temurin-8 / JDK-21 split** to build both sides
- Husky / lint-staged / Node-engine **CI cascade** (modern frontend tooling vs. ancient Node)
- Gradle plugins incompatible with **Gradle 4.10.3**
- The fix: **two Gradle wrappers** (4.10.3 for Grails, 8.x for the services); Grails stays on Java 8 until it's deleted

**Speaker notes.** The least glamorous finding and the one that quietly eats weeks. Grails 3.3 transitively pins Gradle 4.10, which needs JDK 8, which constrains everything else, including the Node version the frontend toolchain can use. You end up running two JDKs side by side (a Temurin 8 for Grails, a 21 for the services), maintaining two Gradle wrappers, and fighting CI because modern husky/lint-staged/Node-engine expectations collide with the ancient runtime. There's no clever fix — the architecture is "isolate the old toolchain and don't let it touch the new one": separate wrappers, separate JDKs, and a hard rule that Grails stays on Java 8 until Phase 12 deletes it. **🤖 What the AI got wrong:** it repeatedly suggested toolchain/plugin versions that are correct for *current* Gradle/Spring but incompatible with the pinned 4.10.3 — again the thin-corpus effect, defaulting to the modern norm. **The habit for architects:** budget explicit time for legacy-toolchain pain, and physically isolate old and new build chains so they can evolve independently. Don't let anyone tell you "it's just a version bump."

---

## Slide 20 — Capstone (Phase 6.5): the *plan* can lie too

- We sat down to plan the next inventory slice — and found the **inventory phase had been mis-sliced**
- **Two false load-bearing scope claims** in one domain:
  - `inventory.warehouse_id` exists *(it doesn't)*
  - "the bulk import does product create-or-find via HTTP" *(it doesn't — that endpoint **rejects** unknown products; create-or-find is a **different** endpoint)*
- Two wrong claims about one domain ⇒ **the decomposition was done above the code**
- The read/write split had cut **straight across a runtime coupling it never named**

**Speaker notes.** This is the planning-level twin of Finding 1, and it's the freshest, richest case study — the "even more from Phase 6.5" material. We didn't write a line of production code; we just tried to *plan* the next slice and discovered the slice boundary itself was wrong. Two of the inventory phase's load-bearing scope claims turned out false when read against the actual code: the `warehouse_id` column doesn't exist (same bug as Finding 1, one altitude up), and the "bulk import creates products via a sync HTTP call to catalog" premise was backwards — the named import endpoint *rejects* unknown products; the create-or-find behavior lives on an entirely *different* endpoint that belongs to the catalog context. The diagnosis: one wrong claim is bad luck; **two wrong claims about one domain is a method problem** — the decomposition was written *above* the code instead of *from* it. And the deeper issue: the phase had been split into "reads" and "writes," but that line cut straight through a runtime coupling nobody had mapped. Which brings us to the keystone.

---

## Slide 21 — Capstone: decompose by coupling, sequence by demand

- The **keystone**: writing a `Transaction` fans out to a **ProductAvailability refresh** via **GORM lifecycle hooks** — the read/write split severed exactly this
- Its nature decides everything: **recompute-from-truth** (idempotent → dual-writer transition is safe) and **no process-independent trigger** (out-of-process writes get *no* refresh)
- **"Phase N.5 / the rest of X" is a smell, not a slice** — it hid *two* blocker classes (intra-service keystone vs. cross-service saga) under one label
- **Sequence by measured demand:** nginx routing × React's API surface × git churn — the roadmap's "next" phase (Ordering) was **dormant** (`purchaseOrder` untouched since 2024); the real demand (cycle count, stock movement) sat unmigrated
- The cure: **name the keystone; classify every deferred item as keystone-blocked or saga-blocked**

**Speaker notes.** Four transferable rules, each earned. (1) **Decompose by runtime coupling, not org-chart/data boundaries.** The thing that actually binds inventory together is that writing a `Transaction` triggers a `ProductAvailability` recompute through a GORM lifecycle hook — and the phase had been sliced into "reads" and "writes," cutting that coupling in half, which is why "Phase 6.5 = inventory writes" had no tractable starting point. Before you slice a domain, map *what fires when its core entity is written* — events, hooks, cascades, jobs — and make a slice own a *whole* coupling. (2) The keystone's *nature* dictates the migration mechanics: this refresh recomputes from source data (so it's idempotent, so a Grails-and-service dual-writer transition is safe *if* you prove the two computations are byte-identical), but it has no independent trigger (so an out-of-process write gets no refresh at all — there's no safety net). You can't know how to move it until you've read those two properties. (3) **"Phase N.5" or "the rest of X" is a deferral label, not a slice** — it bundled an *intra-service* refresh keystone together with a *cross-service* saga, two completely different blocker classes, under one comfortable name. When you see "the rest of X" in a plan, stop and re-decompose. (4) **Sequence by measured demand, not the stated roadmap:** they computed live coupling from nginx routing × React's actual API calls × git churn, and found the roadmap's *recommended* next phase (Ordering) was dormant — its core entity hadn't been touched since 2024 — while the genuinely demanded work (cycle count, stock movement) sat unmigrated. **🤖 The AI angle here is the punchline of the whole class:** the empirical-verification discipline caught *both* mis-slices before they shipped as bugs. The process worked. The only cost was discovering the inventory mis-slice at "6.5" instead of at design time — so the remedy is to pull the verification *earlier*: verify phase scope and coupling when the *phase* is defined, not when its plan is written.

---

## Slide 22 — The through-line

- Finding 1: the **domain class** is not the source of truth
- Finding 4: the **frontend contract you assume** is not the source of truth
- Capstone: the **phase plan** is not the source of truth
- **Same lesson, three altitudes: trust the running system; verify everything written down**
- 🤖 The honest reframe: Grails isn't "too hard for AI" — it's **structurally hard for anyone who didn't write it.** The thin AI corpus just made the hidden assumptions *visible* — and the verification disciplines are the cure either way

**Speaker notes.** This is the payoff of the braided narrative — land it cleanly. Three of the six findings are literally the same insight at different altitudes: code, contract, plan. In each case the written artifact had quietly diverged from the running reality, and the only defense was to go check the reality. Now close the AI loop honestly: the temptation is to conclude "AI is bad at Grails." That's false and useless. The real story is that Grails' defining traits — convention-driven runtime behavior, schema that drifts from code, implicit API contracts — make it *structurally* hard for *any* engineer who wasn't the original author, and the AI simply hit those walls faster and, crucially, *logged every collision*. So nearly every discipline in this talk — DESCRIBE-first, `ddl-auto: create`, capture-the-real-payload, verify-the-plan-against-the-code — is a direct, specific response to a Grails characteristic. That's the gift to your students: not "here's a tool," but "here's *why* your modernization is uniquely treacherous, and the exact habits that make it tractable."

---

## Slide 23 — Your day-one checklist

- **Schema, not domain class:** `DESCRIBE` every table; `SELECT DISTINCT class` for inheritance; run `ddl-auto: create` once to surface drift
- **Behavior, not source:** grep every domain for `beforeInsert`/`beforeUpdate`/`transient`/`hasMany`/`belongsTo`/validators
- **Contract, not assumption:** capture real payloads off the running SPA before you touch an endpoint
- **Round-trip, not green test:** seed the DB; diff old-vs-new output byte-for-byte
- **Coupling, not boundary:** map what fires when each core entity is written, *before* you slice
- **Demand, not roadmap:** rank contexts by routing × frontend calls × git churn

**Speaker notes.** End with something they can act on immediately. Frame it as "run this against your own Grails app this week, before you plan anything." Each line maps to a finding: schema→1, behavior→2, contract→4, round-trip→4, coupling→capstone, demand→capstone. (Finding 3 — the per-entity translation judgment — and Finding 5 — toolchain isolation — are the work that follows once the checklist tells you what you're really dealing with.) Offer the repo: all of this is documented in `docs/process/` and the retrospectives if they want the unabridged version with file-and-line evidence.

---

## Slide 24 — Q&A / references

- Architecture review · parent design spec · 8 retrospectives · 56 adversarial reviews
- Process docs: `synthetic-payload-blind-spot.md`, `phase-decomposition-and-sequencing.md`, `sdd-reviewer-checklist.md`
- Teaching brief: `docs/lessons/2026-06-02_…-teaching-brief.md`
- *Questions?*

**Speaker notes.** Anticipated questions and crisp answers: **"Why not a clean schema-per-service from day one?"** — because that's a distributed-monolith-or-CDC project of its own; transitional shared-DB with per-service table ownership is the smaller move that still lets you ship slice #1. **"Why no message broker?"** — the outbox-relay pattern covers the consistency needs without operating Kafka; add the broker when scale demands it, not before. **"How much of this is AI-specific?"** — almost none of the *findings*; the AI just surfaced them faster and documented them honestly. **"Could you have avoided the Phase 6.5 mis-slice?"** — yes, by applying the same empirical verification to *phase scope* that we already applied to specs; that's the one change we'd make. Close by pointing them at the repo — the entire flight recorder is open.
