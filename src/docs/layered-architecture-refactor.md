# Layered Architecture Refactor — Ports & Adapters (Hexagonal)

- **Status:** Implemented (commits `fa97a00` … `395ef7e` on `refactor-layered-architecture`)
- **Branch:** `refactor-layered-architecture`
- **Date:** 2026-06-21
- **Scope:** Internal package/structure refactor of `smart-doc-analyzer_backend`. **No change to HTTP API, DB schema, or runtime behavior.**

> **As-built note.** This document was written as the design proposal and then kept as the record.
> Sections 1–8 have been updated to match what shipped; **§9** is the actual phase log (with the
> deviations from the original plan), and **§12** summarises the notable as-built differences. The
> refactor was verified at runtime (`./gradlew bootRun` against Postgres) after the persistence and
> orchestration phases, in addition to the unit/slice suite staying green at every commit.

---

## 1. Goal & motivation

Today the code is organized **by technical kind** (`controller`, `service`, `repository`, `entity`,
`dto`, `config`). Business logic, framework concerns, and I/O are entangled — e.g.
`DocumentAnalysisService` directly imports JPA repositories, PDFBox, Spring's `SseEmitter`, Jackson,
and `@Async`/`@Value`. That couples the core analysis logic to four frameworks and makes it hard to
unit-test in isolation or swap an external system (Ollama ↔ Anthropic, filesystem ↔ blob storage).

The refactor moves to a **strict ports & adapters (hexagonal)** layout:

- A **framework-free domain** holds the model and the analysis orchestration. It depends only on the
  JDK, Jackson (`com.fasterxml.jackson.*`), and the SLF4J API — **no Spring, no JPA, no Servlet, no
  PDFBox, no Reactor**.
- Everything framework-specific becomes an **adapter** sitting behind a **port** (a domain interface).
- Dependencies point **inward**: `web → domain ← persistence`, `web → domain ← integration`. The
  domain depends on nobody.

### Non-goals
- No new features, endpoints, or schema changes.
- No switch away from Spring MVC / Spring Data JPA as the runtime.
- Auth remains the current single-default-user model (`CurrentUserProvider`); we only relocate it.

---

## 2. Target package layout

Base package: `com.example.docanalyzer`

```
docanalyzer
├── web/                         INBOUND adapter (HTTP)
│   ├── DocumentController       (magic-byte validation still inline — see §12)
│   ├── dto/                     DocumentUploadResponse, DocumentDetailResponse,
│   │                            AnalysisResultDto, AnalysisProgressEvent
│   ├── SseProgressNotifier      implements domain ProgressNotifier (owns the emitter map)
│   ├── AsyncAnalysisLauncher    @Async wrapper → calls domain use case
│   ├── CurrentUserProvider      resolves the current user (concrete; no port — see §12)
│   ├── GlobalExceptionHandler   @RestControllerAdvice for built-ins + IllegalArgument
│   └── UploadSizeExceptionHandler  413 for oversized uploads (split out — see §12)
│
├── domain/                      CORE — no Spring / JPA / Servlet / PDFBox / Reactor
│   ├── model/                   Document, AnalysisResult, User, FileType,
│   │                            DocumentStatus, AnalysisProgress
│   ├── port/
│   │   ├── in/                  DocumentAnalysisUseCase, UploadCommand
│   │   └── out/                 DocumentRepositoryPort, UserRepositoryPort,
│   │                            StoragePort, TextExtractorPort, LlmPort,
│   │                            ProgressNotifier
│   └── service/                 DocumentAnalysisService (orchestration),
│                                LlmResponseParser, TextChunker, ChunkingConfig
│
├── persistence/                 OUTBOUND adapter (DB)
│   ├── entity/                  DocumentEntity, AnalysisResultEntity, UserEntity (@Entity)
│   ├── repository/              DocumentJpaRepository, UserJpaRepository
│   ├── DocumentRepositoryAdapter implements DocumentRepositoryPort (@Transactional here)
│   ├── UserRepositoryAdapter     implements UserRepositoryPort
│   └── mapper/                  PersistenceMapper (entity ↔ domain model)
│
├── integration/                 OUTBOUND adapters (external systems)
│   ├── llm/                     LlmClient (Ollama/Anthropic WebClient) implements LlmPort
│   ├── storage/                 FilesystemStorageAdapter implements StoragePort
│   └── extraction/              PdfBoxTextExtractor implements TextExtractorPort
│
└── config/                      Spring wiring & cross-cutting
    ├── AsyncConfig, WebMvcConfig
    └── DomainConfig             wires adapters into the domain service (constructor injection)
```

> **Cleanup (done):** the stray empty directory literally named
> `src/main/java/com/example/docanalyzer/{controller,service,repository,entity,dto,config,mapper}`
> — a botched shell brace-expansion artifact — was removed in Phase 1.

---

## 3. The dependency rule

| Layer        | May depend on                                  | Must NOT depend on               |
|--------------|------------------------------------------------|----------------------------------|
| `web`        | `domain` (ports + model)                       | `persistence`, `integration`     |
| `domain`     | JDK, Jackson, SLF4J only                       | `web`, `persistence`, `integration`, Spring, JPA, Servlet, PDFBox, Reactor |
| `persistence`| `domain` (model + out-ports), Spring Data, JPA | `web`, `integration`             |
| `integration`| `domain` (model + out-ports), Spring, WebClient, PDFBox | `web`, `persistence`    |
| `config`     | everything (wiring only)                       | —                                |

Enforced mechanically by an **ArchUnit** test (see §8). The domain having no framework imports is the
single most important invariant — it is what makes the core unit-testable with plain JUnit + mocks.

---

## 4. Domain model (framework-free)

The JPA-annotated classes in `entity/` are **split in two**:

1. **Domain model** (`domain/model/`) — plain Java records/classes, no annotations. These are what the
   domain service and ports speak. `Document`, `AnalysisResult`, `User`, plus the `FileType` /
   `DocumentStatus` enums and a new `AnalysisProgress` value object (replacing the `AnalysisProgressEvent`
   DTO inside the core; the web DTO stays for the wire format).
2. **Persistence entities** (`persistence/entity/`) — `DocumentEntity`, `AnalysisResultEntity`,
   `UserEntity` keep all the JPA mapping (`@Entity`, `@OneToOne`, `@JdbcTypeCode(ARRAY)`, timestamps,
   the `documents`/`analysis_results`/`users` tables). A `PersistenceMapper` translates entity ↔ domain
   model at the adapter boundary.

**As built:** the domain `Document`/`AnalysisResult`/`User` are **mutable Lombok `@Getter/@Setter`
POJOs**, not immutable records. The orchestrator builds an `AnalysisResult` by mutation and the
persistence adapter performs the writes explicitly (it never relies on JPA dirty-checking of a domain
object — it maps the domain result onto a fresh entity). Mutable POJOs were chosen over records to keep
the mutation-style result-building and the test fixtures (`new Document(); doc.setX(...)`) simple; the
enums `FileType`/`DocumentStatus` are top-level in `domain/model` and the JPA entities reference them
directly, so the mapper needs no enum translation.

---

## 5. Ports

### Inbound (driven by `web`)

```text
DocumentAnalysisUseCase
  Document upload(UploadCommand cmd)        // store file + persist PENDING doc
  void     analyze(UUID documentId)         // run the full pipeline (synchronous, pure)
  boolean  delete(UUID id, UUID ownerId)
```

`DocumentAnalysisUseCase.analyze` is **synchronous** in the domain. The `@Async` hop lives in
`web/AsyncAnalysisLauncher`, keeping `@Async`/threading out of the core. There is **no separate
`ManageDocumentsUseCase`**: the read queries (`get`, `list`) and the stream-ownership check call
`DocumentRepositoryPort` directly from the controller, which is a `web → domain` dependency and so is
allowed (see §12).

### Outbound (implemented by `persistence` / `integration`; `ProgressNotifier` by `web`)

```text
DocumentRepositoryPort        save, findById, findByIdAndOwner, findByIdAndOwnerWithResult,
  (persistence, @Transactional) findAllByOwnerWithResults, loadWithResult, updateStatus,
                              completeAnalysis, failAnalysis, deleteAndReturnPath
UserRepositoryPort            findByEmail, save                       (persistence)
StoragePort                   store(InputStream, filename), readBytes, delete   (integration/storage)
TextExtractorPort             String extractText(byte[] content, String contentType)
  (integration/extraction — PDFBox; returns "" for non-PDF content)
LlmPort                       analyzeText, analyzeTextChunk, mergePartialSummaries, analyzeImage
  (integration/llm)
ProgressNotifier              void publish(UUID docId, AnalysisProgress); void complete(UUID docId)
  (web/SseProgressNotifier)
```

`CurrentUserProvider` was **not** turned into a port: it stayed a concrete `web` component
(`web/CurrentUserProvider`) that the controller calls and that uses `UserRepositoryPort`. See §12.

**Transaction boundaries** stay coarse and live in the `persistence` adapter (mirroring today's
`DocumentPersistenceService`): each intention-revealing write (`updateStatus`, `completeAnalysis`,
`failAnalysis`, `deleteAndReturnPath`) is its own `@Transactional` adapter method. The domain
orchestrator calls them in sequence exactly as `analyzeAsync` does now.

**SSE relocation:** the `ConcurrentHashMap<UUID, SseEmitter>` and all `SseEmitter` lifecycle handling
move into `web/SseProgressNotifier`. The domain emits progress by calling `ProgressNotifier.publish(...)`
with a plain `AnalysisProgress`; it never sees `SseEmitter`. The controller's `/stream` endpoint asks
the notifier to register/return an emitter.

---

## 6. Class-by-class mapping

| Current                              | Target                                                            | Notes |
|--------------------------------------|-------------------------------------------------------------------|-------|
| `controller/DocumentController`      | `web/DocumentController`                                           | depends on the use-case port + `DocumentRepositoryPort` |
| `controller` magic-byte validation  | _still inline in `web/DocumentController`_                        | **not** extracted to a `UploadValidator` (deferred — see §12) |
| `dto/*`                              | `web/dto/*`                                                       | `DocumentDetailResponse`/`UploadResponse` reference domain enums, not entities |
| `dto/AnalysisProgressEvent`          | `web/dto/AnalysisProgressEvent` (+ domain `AnalysisProgress`)     | wire DTO stays; core uses domain VO |
| `service/DocumentAnalysisService`    | `domain/service/DocumentAnalysisService` + `web/AsyncAnalysisLauncher` | core logic pure; `@Async` hop in web |
| chunking (`chunkText`, map-reduce)   | `domain/service/TextChunker` + orchestrator                       | already static/pure — easy move |
| JSON parsing (`extractJsonCandidates`, `parseAndApplyLlmResponse`) | `domain/service/LlmResponseParser` | uses Jackson (allowed in domain) |
| `service/DocumentPersistenceService` | `persistence/DocumentRepositoryAdapter`                           | becomes the `DocumentRepositoryPort` impl, keeps `@Transactional` |
| `service/StorageService`             | `integration/storage/FilesystemStorageAdapter` (impl `StoragePort`) | path-traversal guards preserved |
| `service/LlmService`                 | `integration/llm/LlmClient` (impl `LlmPort`)                      | WebClient/Reactor confined here |
| `service/CurrentUserProvider`        | `web/CurrentUserProvider` (concrete; uses `UserRepositoryPort`)   | default-user bootstrap stays; not made a port (§12) |
| PDF text extraction (in analysis svc)| `integration/extraction/PdfBoxTextExtractor` (impl `TextExtractorPort`) | PDFBox confined here; takes `byte[]`+contentType |
| `entity/Document,AnalysisResult,User`| `persistence/entity/*Entity` **+** `domain/model/*`               | split: JPA entity ↔ plain model; `@Entity(name=…)` keeps JPQL/schema stable |
| `repository/DocumentRepository`      | `persistence/repository/DocumentJpaRepository`                    | named-query JPQL preserved |
| `repository/UserRepository`          | `persistence/repository/UserJpaRepository`                        | |
| `config/*`                           | `config/*` (unchanged) + new `DomainConfig` for wiring            | |
| `config/GlobalExceptionHandler`      | `web/GlobalExceptionHandler` + new `web/UploadSizeExceptionHandler` | split to fix a latent ambiguous-mapping bug (§12) |

---

## 7. Cross-cutting concerns — where they land

- **Async / threading** → `web/AsyncAnalysisLauncher` (`@Async("analysisExecutor")`), executor still
  defined in `config/AsyncConfig`. Domain stays single-threaded and pure.
- **Transactions** → `persistence` adapter methods only.
- **SSE** → `web/SseProgressNotifier`.
- **`@Value` config** (chunking thresholds, AI provider, storage dir, CORS) → injected into the
  **adapters/config**, then passed to domain via constructor params/POJO config objects. The domain
  reads no `@Value`. A `ChunkingConfig` record is built in `config/DomainConfig` and constructor-injected
  into `DocumentAnalysisService`.
- **Jackson** → permitted in domain (`LlmResponseParser`), since it is a plain library, not a framework.
- **Validation** → multipart/magic-byte checks **remain inline in `web/DocumentController`**; extracting
  them to a dedicated `UploadValidator` was deferred (see §12).

---

## 8. Enforcement (ArchUnit)

`com.tngtech.archunit:archunit-junit5` (test scope) backs `LayeredArchitectureTest`. The final,
tightened rules (Phase 7) are:

```java
layeredArchitecture().consideringOnlyDependenciesInLayers()
  .layer("Web").definedBy("..web..")
  .layer("Domain").definedBy("..domain..")
  .layer("Persistence").definedBy("..persistence..")
  .layer("Integration").definedBy("..integration..")
  // Domain is the only layer anything may depend on…
  .whereLayer("Domain").mayOnlyBeAccessedByLayers("Web", "Persistence", "Integration")
  // …and the inbound/outbound adapters are leaf layers: nothing in the
  // hexagon depends on them (config wires them by port type via DI and is
  // intentionally outside the layer set).
  .whereLayer("Web").mayNotBeAccessedByAnyLayer()
  .whereLayer("Persistence").mayNotBeAccessedByAnyLayer()
  .whereLayer("Integration").mayNotBeAccessedByAnyLayer();
```

Plus a no-classes-in-`..domain..`-may-depend-on `org.springframework..`, `jakarta.persistence..`,
`jakarta.servlet..`, `org.apache.pdfbox..`, `reactor..` rule. This is the executable contract for §3.
During Phases 1–6 these rules used `withOptionalLayers(true)`/`allowEmptyShould(true)` so they passed
while layers were still being populated; Phase 7 removed those escape hatches.

---

## 9. Phase log (as executed)

Each phase is one commit; the suite stayed green at every step, and the app was boot-verified against
Postgres after Phases 3 and 4. Deviations from the original plan are called out.

1. ✅ **Scaffold packages + ArchUnit contract** (`fa97a00`). `package-info.java` per package; ArchUnit
   added with relaxed rules; stray brace-expansion dir removed; `.gitignore` adjusted so `domain.port.out`
   isn't swallowed by the IDE `out/` rule.
2. ✅ **Extract the three integration adapters behind ports** (`d79289d`): `StoragePort`,
   `LlmPort`, `TextExtractorPort` (new). **Deviation:** the repository ports were **not** done here —
   they were deferred to Phase 3 because they had to be typed against the domain model that Phase 3
   introduces (doing them in Phase 2 would have meant writing entity-typed ports, then immediately
   rewriting them).
3. ✅ **Domain model + persistence adapters** (`595f39c`): plain model + `PersistenceMapper`; JPA
   entities moved to `persistence/entity` as `*Entity` with `@Entity(name=…)`; `DocumentRepositoryPort`
   /`UserRepositoryPort` + adapters (absorbing the old `DocumentPersistenceService`). **Boot-verified.**
4. ✅ **Framework-free orchestration in `domain/service`** (`6f2e163`): pure `DocumentAnalysisService`
   implementing `DocumentAnalysisUseCase`; `TextChunker`/`LlmResponseParser`/`ChunkingConfig` extracted;
   `ProgressNotifier`/`AnalysisProgress` added. **Deviation:** the original **Phase 5 (relocate SSE +
   async to `web`) was folded into this phase** — `SseProgressNotifier` and `AsyncAnalysisLauncher` were
   created here, and wiring moved to `config/DomainConfig`. **Boot-verified.**
5. — _(folded into Phase 4)._
6. ✅ **Collapse legacy `controller`/`dto`/`service` into `web`** (`fb67b7b`). **Deviations:** the
   `UploadValidator` extraction was **not** done (validation stays inline); `GlobalExceptionHandler` was
   initially kept in `config` because moving it surfaced a latent bug (see next).
   - **Bug fix** (`cc752fa`): `GlobalExceptionHandler.handlePayloadTooLarge(MaxUploadSizeExceededException)`
     duplicated the mapping inherited from `ResponseEntityExceptionHandler`, an *ambiguous* mapping that
     fails when the MVC advice cache is built. Split the 413 handler into a highest-precedence
     `web/UploadSizeExceptionHandler`; both advices now live in `web`. Added `ExceptionHandlerAdviceTest`.
7. ✅ **Tighten ArchUnit to the strict contract** (`395ef7e`): dropped the optional-layer/empty-should
   escape hatches; adapters + web asserted as leaf layers (see §8). Passed unchanged — no stray
   `web → persistence/integration` or cross-adapter dependencies existed.
8. ✅ **Tests + this document.** Domain unit tests run with mocked ports and **zero Spring context**
   (`DocumentAnalysisServiceTest`, `TextChunkerTest`); SSE behaviour covered by `SseProgressNotifierTest`.
   This spec reconciled to as-built.

---

## 10. Risks & open questions

- **Domain model duplication.** Splitting JPA entities from the domain model adds a mapper. This is the
  cost of a framework-free domain; the `PersistenceMapper` is the only place that knows both shapes.
- **Lazy loading** — _handled._ The adapter's read methods are `@Transactional` and map inside the
  transaction; `findByIdAndOwnerWithResult`/`findAllByOwnerWithResults` use `LEFT JOIN FETCH` and map via
  `toDomainWithResult`. The mapper maps `owner` **id-only** (`getId()` on a lazy proxy doesn't initialize
  it), so read paths never trip `LazyInitializationException`. Boot-verified with the real DB.
- **`@CreationTimestamp`/`@UpdateTimestamp`** live on the JPA entity; domain timestamps are read-only
  copies. The domain never authors `updatedAt`.
- **Resolved:** use-case implementations live in `domain/service` (no separate `application/` layer);
  revisit if orchestration grows transaction-script-heavy.

---

## 11. Acceptance criteria

- [x] All existing tests pass; HTTP contract and DB schema untouched (Flyway validates 3 migrations,
      Hibernate `ddl-auto: validate` passes on boot).
- [x] `..domain..` has zero imports of Spring, JPA, Servlet, PDFBox, or Reactor (ArchUnit-verified,
      non-vacuously).
- [x] Every external system (DB, LLM, filesystem, PDF extraction, SSE) is reached only through a port.
- [x] `DocumentAnalysisService` runs in unit tests with mocked ports and **no Spring context**.
- [x] The stray `{controller,...}` directory is removed.
- [x] _(beyond plan)_ The app boots against Postgres; a latent ambiguous-`@ExceptionHandler` bug was
      found and fixed with a regression test.

---

## 12. As-built deviations from the proposal

- **Repository ports landed in Phase 3, not Phase 2** — they had to speak the domain model introduced in
  Phase 3 (see §9).
- **Phase 5 was folded into Phase 4** — SSE + async relocation happened together with the orchestration
  move.
- **No `UploadValidator`** — multipart/magic-byte validation stayed inline in `DocumentController`. A
  clean follow-up, but it doesn't affect the dependency rule.
- **No `CurrentUserProvider` port** — it stayed a concrete `web` component using `UserRepositoryPort`.
  Since the controller and the provider are both in `web`, no port was needed to keep the dependency rule
  intact; one can be introduced when real auth (SecurityContext) lands.
- **No `ManageDocumentsUseCase`** — read queries (`get`/`list`) and the stream-ownership check call
  `DocumentRepositoryPort` directly from the controller. That is a `web → domain` (port) dependency,
  which the rule allows; a dedicated query use case was not worth the indirection yet.
- **Domain model is mutable POJOs**, not immutable records (see §4).
- **`GlobalExceptionHandler` split + bug fix** — moving it to `web` surfaced a real latent
  ambiguous-mapping crash; the 413 handler was split into `web/UploadSizeExceptionHandler` (highest
  precedence) and a regression test was added (see §9, Phase 6).
- **`config/DomainConfig`** is the composition root (the proposal called it `BeanConfig`).
```
