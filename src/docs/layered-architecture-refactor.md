# Layered Architecture Refactor — Ports & Adapters (Hexagonal)

- **Status:** Proposed
- **Branch:** `refactor-layered-architecture`
- **Date:** 2026-06-21
- **Scope:** Internal package/structure refactor of `smart-doc-analyzer_backend`. **No change to HTTP API, DB schema, or runtime behavior.**

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
│   ├── DocumentController
│   ├── dto/                     DocumentUploadResponse, DocumentDetailResponse,
│   │                            AnalysisResultDto, AnalysisProgressEvent
│   ├── UploadValidator          multipart + magic-byte validation
│   ├── SseProgressNotifier      implements domain ProgressNotifier (owns the emitter map)
│   ├── AsyncAnalysisLauncher    @Async wrapper → calls domain use case
│   ├── WebCurrentUserProvider   implements domain CurrentUserProvider port
│   └── GlobalExceptionHandler
│
├── domain/                      CORE — no Spring / JPA / Servlet / PDFBox / Reactor
│   ├── model/                   Document, AnalysisResult, User, FileType,
│   │                            DocumentStatus, AnalysisProgress, AnalysisOutcome
│   ├── port/
│   │   ├── in/                  AnalyzeDocumentUseCase, ManageDocumentsUseCase
│   │   └── out/                 DocumentRepositoryPort, UserRepositoryPort,
│   │                            StoragePort, TextExtractorPort, LlmPort,
│   │                            ProgressNotifier, CurrentUserProvider
│   └── service/                 DocumentAnalysisService (orchestration),
│                                LlmResponseParser, TextChunker
│
├── persistence/                 OUTBOUND adapter (DB)
│   ├── entity/                  DocumentEntity, AnalysisResultEntity, UserEntity (@Entity)
│   ├── repository/              SpringDataDocumentRepository, SpringDataUserRepository
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
    └── BeanConfig               wires adapters into domain services (constructor injection)
```

> **Cleanup:** delete the stray empty directory literally named
> `src/main/java/com/example/docanalyzer/{controller,service,repository,entity,dto,config,mapper}`
> — it is a botched shell brace-expansion artifact, not a real package.

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

The domain `Document` is mutable enough to carry status transitions, or modeled as an immutable record
with `withStatus(...)` copy methods — implementer's choice; **recommend immutable records + explicit
adapter writes** so the domain never relies on JPA dirty-checking.

---

## 5. Ports

### Inbound (driven by `web`)

```text
AnalyzeDocumentUseCase
  Document upload(UploadCommand cmd)        // store file + persist PENDING doc
  void     analyze(UUID documentId)         // run the full pipeline (synchronous, pure)

ManageDocumentsUseCase
  Optional<Document> getForOwner(UUID id, UUID ownerId)
  List<Document>     listForOwner(UUID ownerId)
  boolean            delete(UUID id, UUID ownerId)
```

`AnalyzeDocumentUseCase.analyze` is **synchronous** in the domain. The `@Async` hop lives in
`web/AsyncAnalysisLauncher`, keeping `@Async`/threading out of the core.

### Outbound (implemented by `persistence` / `integration` / `web`)

```text
DocumentRepositoryPort        save, findById, findByIdAndOwner, findByIdAndOwnerWithResult,
  (persistence, @Transactional) findAllByOwnerWithResults, updateStatus, completeAnalysis,
                              failAnalysis, deleteAndReturnPath
UserRepositoryPort            findByEmail, save           (persistence)
StoragePort                   store, load(bytes), delete  (integration/storage)
TextExtractorPort             String extract(Document)    (integration/extraction — PDFBox)
LlmPort                       analyzeText, analyzeTextChunk, mergePartialSummaries, analyzeImage
  (integration/llm)
ProgressNotifier              void publish(UUID docId, AnalysisProgress)   (web/SseProgressNotifier)
CurrentUserProvider           User getCurrentUser()       (web/WebCurrentUserProvider)
```

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
| `controller/DocumentController`      | `web/DocumentController`                                           | depends on use-case ports, not services |
| `controller` magic-byte validation  | `web/UploadValidator`                                             | extracted from controller |
| `dto/*`                              | `web/dto/*`                                                       | `DocumentDetailResponse`/`UploadResponse` reference domain enums, not entities |
| `dto/AnalysisProgressEvent`          | `web/dto/AnalysisProgressEvent` (+ domain `AnalysisProgress`)     | wire DTO stays; core uses domain VO |
| `service/DocumentAnalysisService`    | `domain/service/DocumentAnalysisService` + `web/AsyncAnalysisLauncher` | core logic pure; `@Async` hop in web |
| chunking (`chunkText`, map-reduce)   | `domain/service/TextChunker` + orchestrator                       | already static/pure — easy move |
| JSON parsing (`extractJsonCandidates`, `parseAndApplyLlmResponse`) | `domain/service/LlmResponseParser` | uses Jackson (allowed in domain) |
| `service/DocumentPersistenceService` | `persistence/DocumentRepositoryAdapter`                           | becomes the `DocumentRepositoryPort` impl, keeps `@Transactional` |
| `service/StorageService`             | `integration/storage/FilesystemStorageAdapter` (impl `StoragePort`) | path-traversal guards preserved |
| `service/LlmService`                 | `integration/llm/LlmClient` (impl `LlmPort`)                      | WebClient/Reactor confined here |
| `service/CurrentUserProvider`        | `web/WebCurrentUserProvider` (impl `CurrentUserProvider` port)    | default-user bootstrap stays |
| PDF text extraction (in analysis svc)| `integration/extraction/PdfBoxTextExtractor` (impl `TextExtractorPort`) | PDFBox confined here |
| `entity/Document,AnalysisResult,User`| `persistence/entity/*Entity` **+** `domain/model/*`               | split: JPA entity ↔ plain model |
| `repository/DocumentRepository`      | `persistence/repository/SpringDataDocumentRepository`             | named-query JPQL preserved |
| `repository/UserRepository`          | `persistence/repository/SpringDataUserRepository`                 | |
| `config/*`                           | `config/*` (unchanged) + new `BeanConfig` for wiring              | |
| `GlobalExceptionHandler`             | `web/GlobalExceptionHandler`                                      | |

---

## 7. Cross-cutting concerns — where they land

- **Async / threading** → `web/AsyncAnalysisLauncher` (`@Async("analysisExecutor")`), executor still
  defined in `config/AsyncConfig`. Domain stays single-threaded and pure.
- **Transactions** → `persistence` adapter methods only.
- **SSE** → `web/SseProgressNotifier`.
- **`@Value` config** (chunking thresholds, AI provider, storage dir, CORS) → injected into the
  **adapters/config**, then passed to domain via constructor params/POJO config objects. The domain
  reads no `@Value`. Recommend a `ChunkingConfig` record built in `BeanConfig` and constructor-injected
  into `DocumentAnalysisService`.
- **Jackson** → permitted in domain (`LlmResponseParser`), since it is a plain library, not a framework.
- **Validation** → multipart/magic-byte checks in `web/UploadValidator`; "which file types are
  supported" is a domain rule expressed via `FileType`.

---

## 8. Enforcement (ArchUnit)

Add `com.tngtech.archunit:archunit-junit5` (test scope) and a `LayeredArchitectureTest`:

```java
layeredArchitecture().consideringOnlyDependenciesInLayers()
  .layer("Web").definedBy("..web..")
  .layer("Domain").definedBy("..domain..")
  .layer("Persistence").definedBy("..persistence..")
  .layer("Integration").definedBy("..integration..")
  .whereLayer("Web").mayNotBeAccessedByAnyLayer()
  .whereLayer("Domain").mayOnlyBeAccessedByLayers("Web","Persistence","Integration")
  .whereLayer("Persistence").mayOnlyBeAccessedByLayers("Web") // via config wiring only
  ...
```

Plus a no-classes-in-`..domain..`-may-depend-on `org.springframework..`, `jakarta.persistence..`,
`jakarta.servlet..`, `org.apache.pdfbox..`, `reactor..` rule. This is the executable contract for §3.

---

## 9. Migration plan (incremental, test-green at every step)

Each phase compiles and keeps the existing test suite passing. Do them as separate commits.

1. **Scaffold packages + ArchUnit test** (rule initially relaxed). Delete the stray brace-expansion dir.
2. **Extract outbound adapters behind ports**, one at a time, re-pointing existing callers:
   `StoragePort` → `StorageService` becomes `FilesystemStorageAdapter`; same for `LlmPort`,
   `TextExtractorPort` (new), `DocumentRepositoryPort` (from `DocumentPersistenceService`),
   `UserRepositoryPort`.
3. **Introduce the domain model + `PersistenceMapper`**; adapters return/accept domain types. Wire DTOs
   reference domain enums instead of `Document.FileType`.
4. **Move analysis orchestration** into `domain/service`, injecting ports + `ChunkingConfig`. Extract
   `TextChunker` and `LlmResponseParser`. Remove all framework imports from the orchestrator.
5. **Relocate SSE + async** into `web` (`SseProgressNotifier`, `AsyncAnalysisLauncher`); domain emits via
   `ProgressNotifier`.
6. **Move web classes** (controller, DTOs, validator, exception handler, current-user) into `web`.
7. **Tighten the ArchUnit rules** to the full §3 contract; fix any remaining violations.
8. **Update tests** to match new package locations; add focused domain unit tests that mock ports
   (the payoff — `DocumentAnalysisService` tested with zero Spring context).

---

## 10. Risks & open questions

- **Domain model duplication.** Splitting JPA entities from domain records adds a mapper. This is the
  cost of a framework-free domain; the `PersistenceMapper` is the only place that knows both shapes.
- **Lazy loading.** Today `loadWithResult` relies on `LEFT JOIN FETCH`; the mapper must materialize the
  `AnalysisResult` inside the transaction before returning a detached domain object — confirm no lazy
  access leaks past the adapter.
- **`@CreationTimestamp`/`@UpdateTimestamp`** live on the JPA entity; domain timestamps are read-only
  copies set by the DB. Domain must not try to author `updatedAt`.
- **Open:** do we want a separate `application/` layer for use-case implementations, or keep them in
  `domain/service`? This spec keeps them in `domain/service` for simplicity; revisit if orchestration
  grows transaction-script-heavy.

---

## 11. Acceptance criteria

- [ ] All existing tests pass unchanged in behavior; HTTP contract and DB schema untouched.
- [ ] `..domain..` has zero imports of Spring, JPA, Servlet, PDFBox, or Reactor (ArchUnit-verified).
- [ ] Every external system (DB, LLM, filesystem, PDF extraction, SSE) is reached only through a port.
- [ ] `DocumentAnalysisService` has at least one unit test that runs with mocked ports and **no Spring
      context**.
- [ ] The stray `{controller,...}` directory is removed.
```
