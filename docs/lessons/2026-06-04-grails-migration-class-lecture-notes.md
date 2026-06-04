# Modernizing a Grails Monolith — Lecture Notes

> **What this is.** The full lecture in flowing prose — written to be read aloud or studied from, self-contained. It is the narrative companion to the slide deck (`2026-06-04-grails-migration-class-slides.md`); slide markers like *(Slides 6–7)* let you keep the two in sync. Where the deck's speaker notes are terse prompts, this is the whole argument written out.
> **Audience.** Architects & senior developers modernizing their own Grails application.
> **Two braided narratives.** (1) The migration engineering. (2) The AI-assisted angle — the migration was executed by an AI coding agent under spec-driven discipline, and the retrospectives candidly record where the model's mental model of Grails was wrong. The second story is *why* the disciplines in the first exist.
> **Runtime.** ~75–90 minutes delivered. Part I ≈ 25 min, Part II ≈ 50 min, close + Q&A ≈ 15 min.

---

## Opening *(Slides 1–2)*

Good morning. I want to start by telling you what this talk is *not*. It is not a theory talk about microservices, and it is not a vendor pitch for any tool. It is a post-mortem — an unusually honest one — of an actual, in-flight migration of a large Grails application to Spring Boot and React.

The application is OpenBoxes: an open-source warehouse and inventory management system that has been in production for years. About a hundred and sixty thousand lines of server-side Groovy, a hundred and sixteen domain classes, all sitting on a single shared MySQL schema. If that shape sounds familiar — an aging Grails monolith with a half-finished React layer bolted on — then the lessons here will transfer to your codebase almost line for line, because the things that made this migration hard are not specific to OpenBoxes. They are specific to *Grails*.

There is a twist that makes this project worth your time over any other migration war story. This migration was driven by an AI coding agent, working under tight human discipline: every phase went through a design, an adversarial review, an implementation, and a written retrospective. The reason that matters is not novelty. It is honesty. A human team quietly fixes its own mistakes and rarely writes them down. Here, every wrong assumption was caught by a review or a build failure and logged, with a root cause, in one of fifty-six review documents and eight retrospectives that together read like a flight recorder. So we have something most migration projects never produce: a precise record of *where the model's understanding of Grails was wrong* — and those wrong guesses turn out to be exactly the wrong guesses any engineer makes when they did not write the original application.

So we have two stories running side by side. The migration itself, and what the AI got wrong doing it. I am going to braid them on purpose, because the punchline of the whole class depends on it.

Here is the shape of the next ninety minutes. Part one is the journey: how a single-sentence brief became a thirteen-phase migration, and — more importantly — the exact point where the careful plan stopped working. Part two is the payload: six substantive findings, five about Grails and one about planning itself. For each finding I will tell you how it surfaced, usually painfully; what it actually is at a mid level; and the portable habit you can apply on Monday. You will leave with a one-page checklist you can run against your own application on day one.

There is a single idea threaded through everything: **the artifact you are reading is not the source of truth.** The domain class lies. The frontend contract you assume lies. The phase plan lies. The only reliable witness is the running system, and verification is the cure.

---

## A note on how this was run *(Slide 4)*

Let me be explicit about the AI-assisted process up front, because it is the source of the candor I just promised, not a gimmick.

Every phase followed the same loop. First a design document. Then an adversarial review — and I mean adversarial: the reviewer's job was to attack the design and try to falsify it, not to bless it. Then the implementation, executed by the agent under a rule that it had to stop after every single task for human inspection. Then a retrospective that triaged every issue found, labelled them, and folded the durable lessons back into shared process documents so the next phase could not repeat them.

I want to plant a reframe now and pay it off at the end. The honest observation from the people who ran this is that the AI struggled with Grails noticeably more than it had with comparable Java, C#, or Python projects. Part of that is real and simple: Grails 3 and its GORM persistence layer are a smaller, older body of code than mainstream Spring or .NET or Python, so the model's *recall of the idioms* is thinner, and thin recall produces more confident-but-wrong guesses about runtime behavior. But that is the smaller half of the story. The larger and truer half is that the difficulty is **structural to Grails itself**, and it would bite any engineer who did not write the original app. Reading and writing Groovy was never the bottleneck. The bottleneck was that Grails hides its most important behavior from anyone reading the source. The AI simply hit those walls faster, and — crucially — it wrote down every collision. Hold that thought; it is the thesis.

---

# PART I — THE JOURNEY

## The patient *(Slide 3)*

Let me anchor the scale, because every number here was measured, not estimated. A hundred and sixty-two controllers. A hundred and fifty services — forty-one thousand lines. A hundred and sixteen domain classes. Six hundred and eight GSP views, seventy-seven thousand lines of them. The framework is Grails 3.3.16, which is end of life, and which transitively packages Spring Boot 1.5 — also end of life — so a casual reader might glance at the dependency tree and conclude "we already use Spring Boot," which is not remotely what the brief was asking for.

Two physical facts about this system drive everything that follows. First: all hundred and sixteen domain classes live in one shared database schema with no enforced bounded-context boundaries. Second: there is a React single-page app, but it is wedged *inside* the monolith rather than standing beside it. Keep both in mind. The first is why "microservices" is hard; the second is why the frontend cutover is hard. Both come back as findings.

## It started with an adversarial architecture review *(Slides 6–7)*

The brief that kicked this off was one sentence: modernize away from Grails toward Spring Boot microservices with a React frontend, and — quote — "some migration work has started."

The first thing anyone did was not to write code. It was to run an adversarial review whose explicit job was to *attack that brief* and try to prove it wrong. And it did, immediately. Both halves of the premise were false. There was no backend migration at all — zero Java files, zero Spring Boot services, no Maven or Gradle subproject for any extracted module, nothing. And the React frontend that supposedly represented progress could not be lifted off Grails as it stood: its webpack build literally emits Groovy Server Pages into the Grails view tree, it gets its base URL from a `window.CONTEXT_PATH` variable that Grails injects at render time, and it authenticates using the Grails session cookie. Remove Grails and the React app goes with it.

The review reframed the mental model in a single sentence that I would frame and hang on the wall of any modernization project: *backend migration has not started; the React frontend is the only modernization in progress, and it currently runs as a tenant of the Grails app.* If you had believed the brief — if you had taken "some migration has started" at face value — you would have begun extracting your first service on top of a frontend that physically cannot talk to it.

The review produced seven findings against the brief's assumptions. Do not memorize them; group them. The first three say "the frontend is not what you think": no backend has started, the React app is not independently deployable, and roughly three-quarters of the user interface is still GSP, not React. The last four are the ones that matter architecturally, because each is a physical prerequisite of microservices that the monolith violates. One: a hundred and sixteen domain classes in one shared schema with no boundaries — the data will not split. Two: there is no DTO or contract seam between the domain model and the HTTP layer, so every service you cut will break its callers by accident. Three: authentication is a Grails session cookie validated by a single in-process interceptor that matches every request — that does not survive crossing a service boundary. Four: the Liquibase changelog is tied to the monolith's own release version, so two services cannot evolve the schema independently.

The teachable move here is the very first one: before you plan anything, write down the assumptions in your brief and have someone — or something — try to *falsify* them. The single most expensive mistake in a modernization is to inherit a false premise and build a plan on top of it.

## Four forced decisions, made before any code *(Slide 8)*

The second half of that review did something I want you to copy. It refused to make four decisions on the team's behalf, because each one's consequences span the entire multi-year migration and none of them is cheaply reversible.

The first is strategy: strangler-fig, where you replace the monolith piece by piece; versus a parallel rewrite alongside the old system; versus a greenfield-hybrid where only new functionality goes into new services. The second is sequencing: do you finish the frontend first, do the backend first, or migrate in vertical slices that take one context's backend and frontend together. The third is data ownership during the transition: private schema per service, or a shared database for now, or event-driven replication of reference data. The fourth is authentication across the coexistence window — the years during which the React app must talk to *both* the old Grails app and the new services without making the user log in twice.

The transferable lesson is to separate two kinds of thing cleanly. There are *facts about the code* — the seven findings — and there are *irreversible choices* — these four decisions. Facts you discover. Choices you make deliberately, out loud, with the stakeholders in the room. The review's own recommendation was, in effect, a stop sign: surface these to the humans and choose, before writing a line of code. Every migration has its own version of these four. Name yours first.

## The decisions we actually made *(Slide 9)*

Here is what this project chose, and I want you to read the choices as a single coherent philosophy: minimum viable infrastructure, deferred until the slice that needs it actually arrives.

Strategy: strangler-fig with vertical slices. Each phase removes Grails code as it adds Spring Boot code, so the system is shippable and reversible at every step, and you are never left maintaining a half-migrated screen.

Boundaries: eleven domain-aligned services, drawn along bounded contexts rather than along the Grails package layout — because the Grails "core" package is a shared kernel whose entities actually belong to several different contexts.

Data: a shared MariaDB during transition, with each new service owning its own tables. Cross-service reads begin as direct JDBC against the shared database and flip to HTTP calls only once the owning service exists. This is the pragmatic heart of the whole plan. They deliberately did *not* boil the ocean on schema-per-service or change-data-capture up front; they accepted a transitional shared database so that slice number one could actually ship.

Auth: a JSON Web Token in an HttpOnly cookie, running alongside the existing session cookie during the transition. Grails mints it at first; later the identity service takes over; eventually the session cookie disappears entirely. Notably, no external identity provider — no Keycloak, no Auth0 — just a signed token that both the Grails app and the new services understand.

And cross-service writes: a transactional outbox table polled by a relay that delivers events over HTTP to idempotent subscribers. No message broker. No Kafka, no RabbitMQ. And — this is the key discipline — it was not built until phase seven, the first phase that actually needed a cross-service write.

The meta-lesson for the architects in the room: do not pay for infrastructure before the slice that demands it. Each of these is the *smaller* option that still solves the real problem. That restraint is itself a design skill.

## The roadmap, and its humility clause *(Slide 10)*

Those decisions produced a roadmap of thirteen phases. Phase zero is foundations — nginx routing, the JWT mechanism, and a Playwright end-to-end test harness. Phases one through five are the reference and leaf contexts: Document, Identity, Location, Organization, and Catalog. These are things that many other contexts read but that do not themselves write across boundaries, which is why their saga involvement is, literally, "none." Phase six is Inventory, the single largest extraction. Phases seven through eleven are the heavily-coupled, write-across-boundaries contexts — Ordering, where the saga infrastructure gets built; then Shipping, Requisition, Billing, and Reporting. Phase twelve deletes Grails.

I want you to notice two things. The first is the *shape*: low-coupling reference data first, dense transactional contexts last. That ordering is deliberate — you extract the easy, independent things first to build the muscle and de-risk the template before you reach the hard parts. The second thing is a single sentence written into the plan itself: *"Phase three onward is a recommendation."* The plan explicitly anticipated that the ordering would be revisited once the team had real slice experience. Remember that humility clause. It was the most accurate sentence in the entire document.

## The clean stretch *(Slide 11)*

For the first several slices, the plan simply worked, and I want to give it that credit because the clean stretch is the *achievable* part — it is the part most microservices talks show you, and it is real. Identity, Location, Organization, Catalog: each came up as an independent Spring Boot service behind nginx, with the corresponding Grails code deleted as the slice landed. The per-slice template was almost mechanical — extract the entities, stand up the service, point nginx at it, flip the frontend over. Each phase ended with a git tag and a retrospective. The discipline even improved itself in flight: small cleanup and codify mini-phases hardened shared infrastructure between slices — extracting a shared JWT library, adding a continuous-integration lint gate — so the next phase did not have to re-learn the same lesson.

But the clean stretch is exactly the part that lulls you to sleep. Reference data is independent almost by definition. The moment you reach a context with real runtime coupling, the roadmap stops being a roadmap and becomes a hypothesis.

## Where the roadmap stopped working *(Slide 12)*

This is the hinge of the whole story, so let me be precise about it. Two things broke the careful plan.

The first was Catalog. When we cut the catalog service over to clean, flat, well-designed data-transfer objects, we *silently broke the running React application* — because React had been built against Grails' implicit, nested-object payloads, and nobody had ever written that contract down. That break forced an entire unplanned phase just to reconcile the write contracts. That is finding number four, the marquee dead-end, and we will live through it in detail in part two.

The second was Inventory. It was simply too large and too coupled to migrate in one move, so it was split: a safe, read-only phase six, with everything else deferred to a bucket we called "phase six-point-five." And then, when we actually sat down to plan six-point-five, we discovered that the *split itself* was wrong — that the line we had drawn through inventory cut straight across a coupling nobody had mapped. That is the capstone finding.

Now I can answer the question this whole arc raises: why did the early phases follow a stated order, while every later phase had to be brainstormed individually from scratch? Because the early contexts were genuinely independent — a template and a sequence were enough. The later contexts are bound together by *runtime* coupling: lifecycle hooks that fire on writes, derived data that must be recomputed, transactions that span service boundaries. None of that coupling is captured by the top-level "slice by data ownership" decomposition. And you cannot follow a roadmap across coupling you have not mapped. So from the hinge onward, each remaining phase became its own investigation: map the coupling, measure the real demand, and *then* decide the slice. The richest lessons in this entire project live here, on the far side of the hinge — not in the clean stretch.

---

# PART II — THE FINDINGS

A word on structure before we dive in. Every finding gets the same four beats: how we found it, what it actually is, what it implies for you, and — the second narrative — what the AI got wrong and the habit that caught it. I include "how we found it" deliberately, because the *discovery mechanism* is the most transferable thing in the room: you can install the same tripwires in your own project this week.

## Finding 1 — The domain class is not the source of truth; the live database is *(Slide 14)*

This is the foundational finding, and everything else rests on it.

In Grails, the domain class — the `.groovy` file — *looks* exactly like a schema definition. It declares nullability with `nullable: false`. It declares types. It declares relationships. So the natural assumption, for a human or a model, is that the domain class tells you the shape of the table. On a decade-old application, that assumption is false. Years of automatic migrations drift the real schema away from what the domain class claims, and the domain class quietly becomes a work of fiction.

Here is the cruel part. Hibernate has a setting, `ddl-auto: validate`, whose entire job is supposedly to catch this kind of divergence — and it *does not flag nullability mismatches.* It passes happily while the code says a column is non-null and the database says it is nullable. So the safety net you would reach for has a hole in it exactly where you need it most.

How did we find it? By not trusting the domain class at all. The technique was to run `ddl-auto: create` against a throwaway database to force the divergence into the open, and — more bluntly — to run `DESCRIBE` against the live database before mapping a single entity. That caught a whole cascade of lies. A column the domain swore was non-null was nullable. Identifiers were `CHAR(38)` rather than the assumed type. Booleans were stored as `TINYINT`. An element-collection's inner column names were wrong in all five cases. The single-table-inheritance discriminator — the column Grails uses to record which subclass a row is — stored the *fully-qualified* class name, the whole `org.pih.warehouse` path, where JPA by default writes only the simple name, which is an instant, silent mismatch the moment you map it. And the worst of all, in the inventory phase: the plan was built on a column called `inventory.warehouse_id` that **does not exist.** The real link from a facility to its inventory runs through an entirely different column on a different table.

What did the AI get wrong? It trusted the domain class — and in the inventory case, it trusted a plan's own cited evidence line — exactly as a careful human reading the same files would have. This is not an AI failing; it is a Grails failing that an AI surfaced.

The habit is simple and you should make it a hard rule: before you migrate any GORM domain, go to the database. `DESCRIBE` the table. `SHOW COLUMNS`. Run `SELECT DISTINCT class` on any table that uses inheritance, to see what the discriminator actually contains. The schema is ground truth. The domain class is a hopeful description of it, and hope is not a migration strategy.

## Finding 2 — GORM is runtime magic; static reading under-determines behavior *(Slide 15)*

Finding one was "the schema is not in the file." Finding two is "the *behavior* is not in the file either."

GORM is the embodiment of convention over configuration, and that means the conventions — not the source code — carry the behavior. A domain class is handed dynamic finder methods it never declares. It can have getters marked transient that compute a value on read rather than reading a column. Its `belongsTo` and `hasMany` declarations imply cascade rules that govern what happens to related rows on save and delete. It can have lifecycle hooks — `beforeInsert`, `beforeUpdate`, `beforeDelete` — that fire on every write and do real work, like stamping audit fields. It can have validators that reach across instances. It can carry caching hints. None of this is visible by reading the class from top to bottom. In our corpus, lifecycle hooks alone show up in sixteen separate documents — they are everywhere, and they are invisible.

How did we find it? By producing a JPA entity that *read* as complete and was missing behavior that only existed at runtime. The clearest example is a class called `ProductSupplier`, which has transient getters that compute pricing. There is no column behind them, so a straightforward "map the fields" extraction simply drops the behavior on the floor — and the new service's data-transfer object had to *replicate the computation* by hand to preserve it.

What did the AI get wrong here? This is the very heart of the thin-corpus effect, and it is worth being precise about. The model is genuinely excellent at reading Groovy syntax — that was never the problem. What is thinner is its recall of GORM's *runtime conventions*, compared to, say, its deep familiarity with Spring. So it under-predicts what a domain class does when it runs, and it produces mappings that are plausible and incomplete. And — say it plainly — a human who did not write the application makes the identical mistake, for the identical reason.

The habit: before you trust a domain class, grep it for the tell-tales — `beforeInsert`, `beforeUpdate`, `beforeDelete`, `transient`, `hasMany`, `belongsTo`, custom validators, and the mapping and cache blocks — and then trace what actually fires when you write the entity. Reading is not enough. You have to ask the runtime.

## Finding 3 — GORM to JPA is a translation with real impedance mismatches *(Slide 16)*

Once findings one and two have told you what the entity *really* is, you have to translate it — and the message of finding three is that this is genuine translation requiring judgment, not a mechanical transpile. There is no tool that will convert GORM to JPA and save you, because several GORM constructs have no clean JPA equivalent and each one is a small design decision.

Let me give you the concrete mismatches. A bidirectional `belongsTo` between two entities has no clean one-to-one analog in JPA, so it becomes a nullable many-to-one, and *which* side owns the relationship is a judgment call you have to make. The GORM audit hooks — the `beforeInsert` and `beforeUpdate` that record who created or modified a row and when — become a JPA entity-listener plus an `AuditorAware` implementation; and because authentication is now a token, that implementation reads the current user out of the JWT. GORM validators that compare across instances cannot live on the entity anymore, so they move into the service layer.

And I want to show you one that translated *cleanly*, because it is not all pain. Grails' `tablePerHierarchy false` — joined-table inheritance — maps directly onto JPA's joined inheritance strategy, and the Person-and-User hierarchy came across without any drama at all. So the lesson is not "everything is hard." The lesson is that it is *judgment*, and some of the judgments are easy and some are not, and you cannot know which is which until you look.

What did the AI get wrong? It tended to reach for the construct that is *syntactically* closest — a one-to-one, a plain column — rather than the one that is *behaviorally* correct, because it pattern-matches on shape. The cure was per-entity human review of each translation, and that is precisely the kind of thing the adversarial design reviews were there to catch.

The habit: treat each entity translation as a reviewed decision with a written rationale, not a find-and-replace. The rationale is cheap to write and expensive to omit.

## Finding 4 — The implicit API contract breaks the frontend cutover *(Slides 17–18)*

This is the dramatic centerpiece of the entire class: a real production break, with a documented root cause and a documented recovery. Let me tell it as the story it was.

The Grails API controllers do not return flat, clean JSON. They serialize *nested* domain objects — a product comes back with its supplier object embedded inside it — and they accept writes keyed by the *association name*, like `product` or `supplier`. The React app was coded directly against those shapes. There was no OpenAPI specification, no schema, no contract document anywhere — just URL strings and whatever the controller happened to emit on the day someone wired up that screen.

So when the new catalog service returned clean, flat, properly-designed data-transfer objects, that output was *correct by every modern standard you could name* — and it broke the running application. Because "correct" was not the same as "compatible." React was still sending and expecting the old nested shape, and the new service neither produced nor accepted it.

Now, the gut-punch — *why it was not caught before it shipped.* The migration's tests used synthetic, hand-written payloads that matched the new data-transfer object. So the tests were green. They were green because they tested the new shape against the new shape, while the real single-page app was speaking the old shape entirely outside the test's view. And on top of that, the development database was empty, so no test ever exercised a real write round-trip — there was no data to round-trip. Three separate blind spots stacked on top of one another: a wrong assumption about the contract's shape, synthetic test data that encoded that same wrong assumption, and an empty database that hid every write path. Each one alone is survivable. Stacked, they let a contract break sail through a fully green test suite into the running app.

The recovery was a dedicated phase whose only job was to capture the *real* payloads and reconcile them. And from that pain came three rules that are, in my opinion, the single most portable thing you will take from this class.

First: capture the *real* payload off the running application. Open the browser's network tab, watch what the live single-page app actually sends and receives, and treat *that* as the contract. Never trust a fixture you wrote yourself to define a contract, because all it encodes is your assumption — and it will turn the test green for exactly the wrong reason.

Second: a cutover is a *verification* task, not a *wiring* task. Success is not "React now calls the new URL." Success is "the new service is provably compatible with the old contract, under real data." Re-pointing the URL is the trivial part; proving shape-compatibility is the whole job.

Third: seed the database and do a real round-trip. An empty database hides every write path and makes a broken system look fully tested. The strongest technique the team landed on was to run the *same seeded input* through both the old Grails endpoint and the new service, and diff the two outputs for byte-for-byte equality. That pins the migration to the original's *actual* behavior, rather than to a hand-written expectation that might itself be wrong.

What did the AI get wrong, in one sentence? It assumed a cutover was a wiring task when it was a verification task. And, again, that is the most natural assumption in the world for anyone who did not personally write the frontend against those endpoints.

These three rules are written up in the repository as a process document so that the next phase literally cannot repeat the mistake. If you take one slide home, take this one.

## Finding 5 — Grails 3.x pins a 2018-era toolchain, and it cascades *(Slide 19)*

This is the least glamorous finding and the one that quietly eats the most calendar time, so I will not skip it.

Grails 3.3 transitively pins Gradle 4.10, which requires JDK 8, which in turn constrains the version of Node your frontend toolchain can use. It is a chain of frozen versions, and every modern tool fights it. You end up running two JDKs side by side — a Java 8 for the Grails side, a Java 21 for the services. You maintain two separate Gradle wrappers, one stuck at 4.10 for Grails and a modern 8.x for the services. Your continuous integration fights you because modern frontend tooling — the pre-commit hooks, the linters, the Node engine requirements — collide with the ancient runtime. And modern Gradle plugins simply will not load under Gradle 4.10.

There is no clever fix here, and that is the point. The only viable architecture is isolation: keep the old toolchain in a sealed box and do not let it touch the new one. Two Gradle wrappers. Two JDKs. And a hard rule that Grails stays on Java 8 until the very last phase deletes it entirely.

What did the AI get wrong? It repeatedly suggested toolchain and plugin versions that are correct for *current* Gradle and Spring but incompatible with the pinned 4.10 — the thin-corpus effect once more, defaulting confidently to the modern norm.

The habit, and it is one for the architects specifically: budget explicit, named time for legacy-toolchain pain, and physically isolate the old and new build chains so they can evolve independently. When someone tells you "it's just a version bump," that is the sound of a week disappearing.

## Capstone — The plan can lie too *(Slides 20–21)*

Now the freshest and richest material — the lessons from the phase six-point-five analysis. This is the planning-level twin of finding one, and the thing that makes it special is that we never wrote a line of production code. We simply tried to *plan* the next inventory slice, and in doing so discovered that the slice boundary itself was wrong.

Remember that inventory had been split into a read-only phase six and a deferred "everything else" bucket. When we sat down to scope that bucket, two of the inventory phase's load-bearing scope claims turned out to be false the moment we read them against the actual code. The first was that `warehouse_id` column again — it does not exist; this is finding one, one altitude up. The second was a claim that the bulk-import flow created products by making a synchronous HTTP call to the catalog service. That was backwards. The named import endpoint does not create products; it *rejects* unknown ones. The create-or-find behavior lives on a completely different endpoint that belongs to the catalog context, not the inventory context. The plan had conflated two different features.

Here is the diagnosis, and it is the most important sentence in this section: one wrong claim is bad luck, but *two* wrong claims about a single domain is a method problem. It meant the decomposition had been done *above* the code rather than *from* it.

And underneath those two surface errors was a deeper one. The phase had been split into "reads" and "writes" — and that line ran straight through a coupling nobody had named. Let me name it, because it is the keystone of the whole inventory domain. When you write a `Transaction` in this system, that write fans out, through a GORM lifecycle hook, to a recomputation of product availability — plus a couple of related derived totals. The read/write split severed exactly this coupling, and that is *why* "phase six-point-five equals inventory writes" had no tractable first slice: you cannot own half of a coupling.

The nature of that keystone dictates how it can ever move, and this is the kind of thing you only learn by reading it carefully. Two properties matter. First, the availability refresh recomputes from source data — it is idempotent — which means a transitional state where *both* Grails and the new service write is actually safe, *provided* you have proven the two computations produce identical results. Second, there is no process-independent trigger for that refresh — no background poller, nothing that runs on its own — which means a write that happens *outside* the Grails process gets no refresh at all. There is no safety net. You cannot decide how to migrate the writes until you have read both of those facts.

From that came four rules I want you to carry out of this room.

One: decompose by *runtime coupling*, not by org-chart or data-ownership boundaries. Before you slice a domain, map what fires when its core entity is written — the events, the lifecycle hooks, the cascades, the jobs — and draw your slice so that it owns a *whole* coupling, never half of one.

Two: a phase named "phase N-point-five" or "the rest of X" is a smell, not a slice. It is a deferral label. In our case that one label was hiding two completely different kinds of blocker — an *intra-service* refresh keystone and a *cross-service* saga — bundled together under one comfortable name. When you see "the rest of X" in a plan, stop and re-decompose.

Three: sequence by *measured* demand, not by the roadmap's stated order. The team computed the live coupling empirically — nginx routing, times the React app's actual API surface, times recent git churn — and discovered that the roadmap's *recommended* next phase, Ordering, was dormant; its core entity had not been touched since 2024. Meanwhile the genuinely demanded work — cycle count and stock movement — sat unmigrated. Let measurement, subject to dependency feasibility, choose your next slice.

Four: name the keystone explicitly in every phase design, and classify every deferred item by *which* blocker holds it up — is it blocked by the intra-service keystone, or by a cross-service saga? Those two sequence completely differently, and lumping them together is exactly the mistake we made.

And here is the AI punchline, which is really the punchline of the whole class. The empirical-verification discipline caught *both* mis-slices before either shipped as a bug — the non-existent column, and the backwards import premise, the latter before any code at all. The process worked. The only cost was that we discovered the inventory mis-slice at "six-point-five" rather than at design time. So the remedy is not a new technique; it is to pull the discipline *earlier* — to verify a phase's scope and coupling when the *phase* is defined, not when its plan is written.

---

## The through-line *(Slide 22)*

Step back and look at three of the six findings together. In finding one, the *domain class* was not the source of truth. In finding four, the *frontend contract you assume* was not the source of truth. In the capstone, the *phase plan* was not the source of truth. It is the same lesson at three different altitudes — code, contract, and plan — and in every single case the written artifact had quietly diverged from the running reality, and the only defense was to go and check the reality.

Now let me close the AI loop honestly, because it is tempting to draw the wrong conclusion. The lazy takeaway is "AI is bad at Grails." That is both false and useless. The true story is that Grails' defining characteristics — convention-driven behavior that lives in the runtime rather than the source, a schema that drifts away from the code, API contracts that are implicit and unwritten — make it *structurally* hard for *anyone* who did not write the original application. The AI simply collided with those walls faster than a human would, and, unlike most humans, it documented every collision. That documentation is the gift. Nearly every discipline in this talk — describe the table first, use `ddl-auto: create` to surface drift, capture the real payload, verify the plan against the code — is a direct, specific response to a specific Grails characteristic.

So what your students need from you is not "here is a tool." It is "here is *why* your modernization is uniquely treacherous, and here are the exact habits that make it tractable." That is the whole class in one sentence.

## Your day-one checklist *(Slide 23)*

Leave them with something they can run this week, before they plan anything.

Schema, not domain class: `DESCRIBE` every table, run `SELECT DISTINCT class` on the inheritance tables, and run `ddl-auto: create` once to surface the drift. That is finding one.

Behavior, not source: grep every domain class for `beforeInsert`, `beforeUpdate`, `transient`, `hasMany`, `belongsTo`, and validators, then trace what fires on a write. That is finding two.

Contract, not assumption: capture the real payloads off the running single-page app — from the browser network tab — before you touch a single endpoint. That is finding four.

Round-trip, not green test: seed the database and diff the old and new outputs byte-for-byte under the same input. Also finding four.

Coupling, not boundary: map what fires when each core entity is written, *before* you draw a single slice boundary. That is the capstone.

Demand, not roadmap: rank your contexts by routing, times frontend calls, times git churn, and let that pick your next slice. Also the capstone.

Finding three — the per-entity translation judgment — and finding five — toolchain isolation — are the work that *follows* once this checklist has told you what you are really dealing with.

## Close and anticipated questions *(Slide 24)*

That is the class. Everything I have told you is documented in the repository — the architecture review, the parent design, eight retrospectives, fifty-six adversarial reviews, and a handful of process documents that distil the durable lessons. If you want the unabridged version with file-and-line evidence, it is all open.

Let me pre-empt the four questions I always get.

*Why not a clean schema-per-service from day one?* Because that is a distributed-monolith-or-change-data-capture project in its own right, and it would have blocked slice number one for months. A transitional shared database with per-service table ownership is the smaller move that still lets you ship.

*Why no message broker?* Because the outbox-and-relay pattern covers the consistency needs without anyone having to operate Kafka. Add the broker when scale genuinely demands it — which is to say, not before.

*How much of this is AI-specific?* Almost none of the findings. The AI surfaced them faster and documented them more honestly than a human team would have, but the findings themselves are pure Grails.

*Could you have avoided the phase six-point-five mis-slice?* Yes — by applying the same empirical verification to *phase scope* that we already applied to individual specs. That is the one change we would make, and it is the note I want to end on: pull your verification as early as it will go.

Thank you. Questions.
