# Modernizing a Grails Monolith: Lecture Notes (35-40 min cut)

> **What this is.** The 35 to 40 minute lecture written out as prose, meant to be read aloud or studied from on its own. It is the narrative companion to the short deck (`2026-06-04-grails-migration-class-slides-35min.md`). Slide markers like *(Slide 5)* keep the two in sync.
> **Relationship to the long version.** This is the condensed cut of the 90-min lecture notes (`2026-06-04-grails-migration-class-lecture-notes.md`). It keeps both braided narratives, (1) the migration engineering and (2) where an AI agent's mental model of Grails was wrong, and the spine *the artifact you're reading is not the source of truth*. Part I is a fast setup. Part II goes deep on the **3 "source of truth" findings** and folds the other 3 Grails traps into a single brisk section.
> **Audience.** Architects and senior developers modernizing their own Grails application.
> **Runtime.** About 35 to 40 minutes delivered. Part I about 8 min, Part II about 20 min, close and Q&A about 7 min.

---

## Opening *(Slides 1-2)*

Let me tell you what this talk is *not*. It is not a theory talk about microservices, and it is not a vendor pitch. It is a post-mortem, an unusually honest one, of an actual in-flight migration of a large Grails application to Spring Boot and React.

The application is OpenBoxes, an open-source warehouse and inventory system that has been in production for years. About a hundred and sixty thousand lines of server-side Groovy. A hundred and sixteen domain classes. Six hundred-odd GSP views, all sitting on a single shared schema. The framework is Grails 3.3.16, which is end of life, and it transitively packages Spring Boot 1.5, which is also end of life. So a casual reader might glance at the dependency tree and conclude "we already use Spring Boot," which is not remotely what the brief was asking for.

If that shape sounds like your codebase, good. The lessons transfer almost line for line, because what made this hard is specific to *Grails*, not to OpenBoxes.

Two physical facts drive the entire story, so hold both. First, all hundred and sixteen domain classes live in one shared database schema with no enforced boundaries. That is why "microservices" is hard. Second, there is a React single-page app, but it is wedged *inside* the monolith rather than standing beside it. That is why the frontend cutover is hard.

There is a twist that earns this talk your time over any other war story. The migration was driven by an AI coding agent under tight human discipline. Every phase ran the same loop: a design, then an adversarial review whose job was to *attack* the design, then an implementation under a rule that the agent stop after every task for inspection, then a written retrospective that folded the durable lessons back into shared process docs.

The reason that matters is not novelty. It is honesty. A human team quietly fixes its own mistakes and never writes them down. Here, every wrong assumption was caught by a review or a build failure and logged, with a root cause, in fifty-six review documents and eight retrospectives that together read like a flight recorder. So we have something most migrations never produce: a precise record of where the model's understanding of Grails was wrong. And those wrong guesses are exactly the ones any engineer makes when they did not write the original app.

I will plant the thesis now and pay it off at the end. The honest observation from the people who ran this is that the AI struggled with Grails noticeably more than with comparable Java, C#, or Python work. Part of that is real and simple. Grails 3 and GORM are a smaller, older corpus, so the model's recall of the idioms is thinner, and thin recall produces confident-but-wrong guesses.

But that is the smaller half. The larger and truer half is that the difficulty is **structural to Grails itself**. Grails hides its most important behavior from anyone reading the source. The AI just hit those walls faster, and it documented every collision.

There is a single idea threaded through everything that follows. **The artifact you are reading is not the source of truth.** The domain class lies. The frontend contract you assume lies. The phase plan lies. The only reliable witness is the running system.

---

# PART I: THE JOURNEY (~8 min)

## It started by attacking the brief *(Slide 3)*

The brief that kicked this off was one sentence. Modernize away from Grails toward Spring Boot microservices with a React frontend, and, quote, "some migration work has started."

The first thing anyone did was not to write code. It was to run an adversarial review whose only job was to *falsify that brief*. And it did, immediately, on both halves. There was no backend migration at all. Zero Java files, zero Spring Boot services, no build subproject for any extracted module. And the React frontend that supposedly represented progress could not be lifted off Grails. Its webpack build emits Groovy Server Pages into the Grails view tree, it gets its base URL from a `window.CONTEXT_PATH` variable Grails injects at render time, and it authenticates with the Grails session cookie.

Remove Grails and the React app goes with it.

The reframe is worth hanging on a wall. *Backend migration has not started. The React frontend is the only modernization in progress, and it currently runs as a tenant of the Grails app.* Believe the brief and you would have started extracting your first service on top of a frontend that physically cannot talk to it.

The review produced seven findings. Group them rather than memorize them. The first three say "the frontend is not what you think." The last four are the architectural ones, because each is a physical prerequisite of microservices that the monolith violates. Data is shared across all contexts with no boundaries, so it will not split. There is no DTO or contract seam, so every service you cut breaks its callers by accident. Authentication is an in-process session cookie that does not survive crossing a boundary. And the schema changelog is pinned to the monolith's own release version, so two services cannot evolve it independently.

The shared-data one is the dragon. Split it naively and you get a *distributed monolith*, many services hammering one database, which is strictly worse than where you started.

The transferable move is the very first one. Before you plan anything, write down the assumptions in your brief and have someone (or something) try to falsify them. The most expensive mistake in a modernization is to inherit a false premise and build on it.

## The decisions, and the roadmap *(Slides 4-5)*

The review then did something worth copying. It refused to make four irreversible decisions on the team's behalf (strategy, sequencing, data ownership during the transition, and auth across the coexistence window) because each one's consequences span the whole multi-year migration. Separate the two kinds of thing cleanly. *Facts about the code* you discover. *Irreversible choices* you make deliberately, out loud, with the stakeholders in the room.

Here is what the project chose, and read it as one philosophy: minimum viable infrastructure, deferred until the slice that needs it arrives.

Strangler-fig with vertical slices, so each phase removes Grails code as it adds Spring Boot and the system stays shippable and reversible at every step. Eleven services drawn along bounded contexts, not the Grails package layout. A shared MariaDB during transition, with each new service owning its own tables, and cross-service reads starting as direct JDBC and flipping to HTTP once the owner exists. That last one is the pragmatic heart of the plan. They deliberately did *not* boil the ocean on schema-per-service, so that slice number one could actually ship. For auth, a JSON Web Token in an HttpOnly cookie alongside the session cookie, minted by Grails first and the identity service later. No Keycloak, no external provider. And for cross-service writes, a transactional outbox polled by a relay over HTTP to idempotent subscribers. No Kafka, and not built until phase seven, the first phase that needed one.

Every one of these is the *smaller* option that still solves the real problem. That restraint is itself a design skill.

Those decisions produced a roadmap of thirteen phases. Phase zero is foundations. Phases one through five are the reference and leaf contexts (Document, Identity, Location, Organization, Catalog), things many contexts read but that do not write across boundaries, so their saga involvement is literally "none." Phase six is Inventory, the largest extraction. Seven through eleven are the heavily coupled write-across-boundary contexts. Twelve deletes Grails.

Notice the shape. Low-coupling first, dense transactional contexts last, deliberately, to build the muscle before the hard parts. And notice one sentence written into the plan itself: *"Phase three onward is a recommendation."* The plan anticipated that the ordering would be revisited. Remember that humility clause. It was the most accurate sentence in the whole document.

And for the first several slices, the plan simply *worked*. I want to give it that credit, because the clean stretch is the *achievable* part, the part most microservices talks show you, and it is real. Identity, Location, Organization, and Catalog each came up as an independent Spring Boot service behind nginx, with the corresponding Grails code deleted as it landed. The template was almost mechanical. Extract the entities, stand up the service, point nginx at it, flip the frontend.

But the clean stretch is exactly the part that lulls you to sleep. Reference data is independent almost by definition, and the moment you reach a context with real runtime coupling, the roadmap stops being a roadmap and becomes a hypothesis.

Two things broke it.

First, Catalog. When we cut over to clean, flat data-transfer objects, we *silently broke the running React app*, because React had been built against Grails' implicit nested payloads. That forced an entire unplanned reconciliation phase, and it is Finding 4. Second, Inventory. It was too large and coupled to do in one move, so it was split into a safe read-only phase six and a deferred "phase six-point-five." And when we sat down to plan that bucket, we discovered the *split itself* was wrong. That is the capstone.

And now I can answer the question this arc raises. Why did the early phases follow a stated order while every later phase had to be brainstormed from scratch? Because the early contexts were genuinely independent, so a template and a sequence sufficed. The later ones are bound by *runtime* coupling (lifecycle hooks, derived-data refreshes, cross-service sagas) that the top-level "slice by data ownership" decomposition never captured. You cannot follow a roadmap across coupling you have not mapped. The richest lessons in the whole project live here, on the far side of the hinge.

---

# PART II: THE FINDINGS (~20 min)

A word on structure *(Slide 6)*. There are six findings in the full project, and today I go deep on the three that form the through-line and give you the other three in brief. Each of the deep ones gets four beats: how we found it, what it actually is, what it implies for you, and the second narrative, which is what the AI got wrong and the habit that caught it.

I keep "how we found it" for a reason. The discovery mechanism is the most transferable thing in the room.

The three deep findings are the same insight at three altitudes. In the first, the *domain class* is not the source of truth. In the second, the *frontend contract you assume* is not. In the capstone, the *phase plan* is not.

## Finding 1: The domain class is not the source of truth, the live database is *(Slide 7)*

This is the foundational finding, and everything else rests on it.

In Grails, the domain class, the `.groovy` file, *looks* exactly like a schema definition. It declares nullability with `nullable: false`. It declares types and relationships. So the natural assumption, for a human or a model, is that it tells you the shape of the table. On a decade-old application that assumption is false. Years of automatic migrations drift the real schema away from what the class claims, and the class quietly becomes a work of fiction.

And here is the cruel part. Hibernate's `ddl-auto: validate`, whose whole job is supposedly to catch this, does *not* flag nullability mismatches. It passes happily while the code says non-null and the database says nullable. The safety net you would reach for has a hole exactly where you need it.

How did we find it? By not trusting the class at all. We ran `ddl-auto: create` against a throwaway database to force the divergence into the open, and we bluntly ran `DESCRIBE` against the live database before mapping a single entity. That caught a cascade of lies. A column the domain swore was non-null was nullable. Identifiers were `CHAR(38)`, not the assumed type. Booleans were stored as `TINYINT`. An element-collection's inner column names were wrong in all five cases. The single-table-inheritance discriminator stored the *fully-qualified* class name, the whole `org.pih.warehouse` path, where JPA by default writes only the simple name, which is an instant silent mismatch the moment you map it.

And the worst, in the inventory phase: the plan was built on a column called `inventory.warehouse_id` that *does not exist*.

What did the AI get wrong? It trusted the domain class, and in the inventory case it trusted a plan's own cited evidence line, exactly as a careful human reading the same files would have. This is not an AI failing. It is a Grails failing that an AI surfaced.

The habit, and make it a hard rule: before you migrate any GORM domain, go to the database. `DESCRIBE` the table, run `SHOW COLUMNS`, and run `SELECT DISTINCT class` on any inheritance table to see what the discriminator actually contains. The schema is ground truth. The domain class is a hopeful description of it, and hope is not a migration strategy.

## Finding 4: The implicit API contract breaks the frontend cutover *(Slides 8-9)*

This is the dramatic centerpiece. A real production break, with a documented root cause and a documented recovery. Let me tell it as the story it was.

The Grails API controllers do not return flat, clean JSON. They serialize *nested* domain objects (a product comes back with its supplier embedded inside it) and they accept writes keyed by the *association name*, like `product` or `supplier`. The React app was coded directly against those shapes. There was no OpenAPI specification, no schema, no contract document anywhere. Just URL strings and whatever the controller happened to emit on the day someone wired up that screen.

So when the new catalog service returned clean, flat, properly designed data-transfer objects, that output was *correct by every modern standard you could name*, and it broke the running application. Correct was not the same as compatible. React was still sending and expecting the old nested shape.

Now the gut-punch. *Why was it not caught before it shipped?* The migration's tests used synthetic, hand-written payloads that matched the new data-transfer object, so the tests were green. They were green because they tested the new shape against the new shape, while the real single-page app spoke the old shape entirely outside the test's view. And the development database was empty, so no test ever exercised a real write round-trip.

Three blind spots stacked. A wrong assumption about the contract's shape, synthetic test data that encoded that same wrong assumption, and an empty database that hid every write path. Each one alone is survivable. Stacked, they let a contract break sail through a fully green suite into the running app.

The recovery was a dedicated phase whose only job was to capture the *real* payloads and reconcile them. From that pain came three rules that are, in my opinion, the single most portable thing you will take from this class.

First, capture the *real* payload off the running application. Open the browser's network tab, watch what the live app actually sends and receives, and treat *that* as the contract. Never trust a fixture you wrote yourself, because all it encodes is your assumption, and it will turn the test green for exactly the wrong reason.

Second, a cutover is a *verification* task, not a *wiring* task. Success is not "React now calls the new URL." It is "the new service is provably compatible with the old contract, under real data."

Third, seed the database and do a real round-trip. The strongest technique the team landed on was to run the *same seeded input* through both the old Grails endpoint and the new service and diff the two outputs for byte-for-byte equality. That pins the migration to the original's *actual* behavior rather than to a hand-written expectation that might itself be wrong.

What did the AI get wrong, in one sentence? It assumed a cutover was a wiring task when it was a verification task. That is the most natural assumption in the world for anyone who did not personally write the frontend against those endpoints.

These three rules are written up in the repository as a process document, so the next phase literally cannot repeat the mistake. If you take one slide home, take this one.

## Capstone: The plan can lie too *(Slides 10-11)*

Now the freshest and richest material, the planning-level twin of Finding 1. What makes it special is that we never wrote a line of production code. We simply tried to *plan* the next inventory slice and discovered the slice boundary itself was wrong.

Remember that inventory had been split into a read-only phase six and a deferred "everything else" bucket. When we sat down to scope that bucket, two of the phase's load-bearing scope claims turned out false the moment we read them against the actual code. The first was that `warehouse_id` column again. It does not exist. This is Finding 1, one altitude up. The second was a claim that the bulk-import flow created products by making a synchronous HTTP call to the catalog service. That was backwards. The named import endpoint does not create products, it *rejects* unknown ones, and the create-or-find behavior lives on a completely different endpoint that belongs to the catalog context. The plan had conflated two different features.

Here is the diagnosis, and it is the most important sentence in this section. One wrong claim is bad luck. Two wrong claims about a single domain is a method problem. The decomposition had been done *above* the code rather than *from* it.

And underneath those two surface errors was a deeper one. The phase had been split into "reads" and "writes," and that line ran straight through a coupling nobody had named. Let me name it, because it is the keystone of the whole inventory domain. When you write a `Transaction` in this system, that write fans out, through a GORM lifecycle hook, to a recomputation of product availability. The read/write split severed exactly this, which is *why* "phase six-point-five equals inventory writes" had no tractable first slice.

You cannot own half of a coupling.

And the nature of that keystone dictates how it can ever move, the kind of thing you only learn by reading it carefully. Two properties matter. First, the availability refresh recomputes from source data, so it is idempotent, which means a transitional state where *both* Grails and the new service write is safe, *provided* you have proven the two computations produce identical results. Second, there is no process-independent trigger for that refresh. Nothing runs on its own. So a write happening *outside* the Grails process gets no refresh at all. There is no safety net. You cannot decide how to migrate the writes until you have read both of those facts.

From that came four rules I want you to carry out of this room.

One. Decompose by *runtime coupling*, not by org-chart or data-ownership boundaries. Before you slice a domain, map what fires when its core entity is written, the events and hooks and cascades and jobs, and draw your slice so it owns a *whole* coupling, never half.

Two. A phase named "phase N-point-five" or "the rest of X" is a smell, not a slice. It is a deferral label. In our case it hid two completely different kinds of blocker, an *intra-service* refresh keystone and a *cross-service* saga, bundled under one comfortable name. When you see it, stop and re-decompose.

Three. Sequence by *measured* demand, not the roadmap's stated order. The team computed live coupling empirically, nginx routing times the React app's actual API surface times recent git churn, and found the roadmap's recommended next phase, Ordering, was dormant, its core entity untouched since 2024, while the genuinely demanded work (cycle count and stock movement) sat unmigrated.

Four. Name the keystone explicitly in every phase design, and classify every deferred item by *which* blocker holds it up, because those sequence completely differently.

And here is the AI punchline, which is really the punchline of the whole class. The empirical-verification discipline caught *both* mis-slices before either shipped as a bug, the non-existent column and the backwards import premise, the latter before any code at all. The process *worked*. The only cost was that we discovered the inventory mis-slice at "six-point-five" rather than at design time. So the remedy is not a new technique. It is to pull the discipline *earlier*, to verify a phase's scope and coupling when the *phase* is defined, not when its plan is written.

## Three more Grails traps, in brief *(Slide 12)*

The other three findings are real and they matter, but they are the work that *follows* the spine, so let me give them to you fast.

The first is that **GORM is runtime magic**. You cannot tell how a domain persists by reading it. A class is handed dynamic finders it never declares. It can have transient getters that compute a value on read instead of reading a column. Its `belongsTo` and `hasMany` declarations imply cascade rules. It can have lifecycle hooks that fire on every write, and those alone show up in sixteen of our documents, plus validators that reach across instances. None of it is visible reading top to bottom, so a "map the fields" extraction silently drops behavior. The clearest example is `ProductSupplier`, whose transient getters compute pricing with no column behind them, so the new service's DTO had to *replicate* the computation. The habit is to grep every domain for the tell-tales and trace what actually fires on a write.

The second is that **GORM-to-JPA is a translation, not a transpile**. There is no converter that will save you, because each entity is a small reviewed design decision. A bidirectional `belongsTo` has no clean one-to-one analog, so it becomes a nullable many-to-one with a judgment call about ownership. The audit hooks become a JPA entity-listener plus an `AuditorAware` that reads the user from the JWT. Cross-instance validators move to the service layer. And some translate cleanly. `tablePerHierarchy false` maps straight onto joined-table inheritance, and Person-and-User came across without any drama. It is judgment, not pain. Treat each translation as a reviewed decision with a written rationale.

The third is the least glamorous and the one that quietly eats weeks. **Grails 3.x pins a 2018-era toolchain.** Grails 3.3 transitively pins Gradle 4.10, which requires JDK 8, which constrains the Node version your frontend tooling can use, and it cascades into CI. There is no clever fix. The only architecture that works is isolation: two Gradle wrappers, two JDKs side by side, and a hard rule that Grails stays on Java 8 until the last phase deletes it. Budget explicit, named time for it. When someone says "it's just a version bump," that is the sound of a week disappearing.

The unifying AI note across all three is that the model kept defaulting confidently to the *modern norm*, the current JPA construct, the current Gradle and plugin versions, because its recall of the older idioms is thinner. The thin-corpus effect again, and exactly the trap a human who only knows modern Spring would fall into.

---

## The through-line *(Slide 13)*

Step back and look at the three deep findings together. In the first, the *domain class* was not the source of truth. In the second, the *frontend contract you assume* was not. In the capstone, the *phase plan* was not. It is the same lesson at three altitudes, code and contract and plan, and in every case the written artifact had quietly diverged from the running reality. The only defense was to go and check the reality.

Now let me close the AI loop honestly, because it is tempting to draw the wrong conclusion. The lazy takeaway is "AI is bad at Grails."

That is both false and useless.

The true story is that Grails' defining characteristics make it *structurally* hard for *anyone* who did not write the original application. Convention-driven behavior that lives in the runtime rather than the source. A schema that drifts away from the code. API contracts that are implicit and unwritten. The AI simply collided with those walls faster than a human would, and, unlike most humans, it documented every collision.

That documentation is the gift. Nearly every discipline in this talk is a direct, specific response to a specific Grails characteristic: describe the table first, use `ddl-auto: create` to surface drift, capture the real payload, verify the plan against the code. What your students need from you is not "here is a tool." It is "here is *why* your modernization is uniquely treacherous, and here are the exact habits that make it tractable."

## Your day-one checklist *(Slide 14)*

Leave them with something they can run this week, before they plan anything.

Schema, not domain class. `DESCRIBE` every table, run `SELECT DISTINCT class` on the inheritance tables, and run `ddl-auto: create` once to surface the drift. That is Finding 1. Behavior, not source. Grep every domain for `beforeInsert`, `beforeUpdate`, `transient`, `hasMany`, `belongsTo`, and validators, then trace what fires on a write. That is Finding 2. Contract, not assumption. Capture the real payloads off the running single-page app before you touch a single endpoint. That is Finding 4. Round-trip, not green test. Seed the database and diff old and new outputs byte-for-byte under the same input. Also Finding 4. Coupling, not boundary. Map what fires when each core entity is written, *before* you draw a slice boundary. That is the capstone. Demand, not roadmap. Rank your contexts by routing times frontend calls times git churn, and let that pick your next slice. Also the capstone.

The two findings we compressed today, the per-entity translation judgment and toolchain isolation, are the work that *follows* once this checklist has told you what you are really dealing with.

## Close and anticipated questions *(Slide 15)*

That is the class. Everything I have told you is documented in the repository: the architecture review, the parent design, eight retrospectives, fifty-six adversarial reviews, and a handful of process documents that distil the durable lessons. The full ninety-minute deck and lecture notes sit right alongside this one if you want the unabridged version with file-and-line evidence.

Four questions I always get.

*Why not a clean schema-per-service from day one?* Because that is a distributed-monolith-or-change-data-capture project in its own right, and it would have blocked slice number one for months. A transitional shared database with per-service table ownership is the smaller move that still lets you ship.

*Why no message broker?* Because the outbox-and-relay pattern covers the consistency needs without anyone having to operate Kafka. Add the broker when scale genuinely demands it, not before.

*How much of this is AI-specific?* Almost none of the findings. The AI surfaced them faster and documented them more honestly, but the findings themselves are pure Grails.

*Could you have avoided the phase six-point-five mis-slice?* Yes, by applying the same empirical verification to *phase scope* that we already applied to individual specs. That is the one change we would make, and it is the note I want to end on. Pull your verification as early as it will go.

Thank you. Questions.
