# Smart Document Analyzer — Spring Boot Backend

AI-powered document analysis using Ollama (local) or the Anthropic API.

## Stack
- **Spring Boot 3.3** / Java 21
- **PostgreSQL** + Flyway migrations
- **Apache PDFBox** for PDF text extraction
- **WebFlux WebClient** for LLM API calls
- **SSE** for real-time analysis progress streaming

## Prerequisites
- Java 21+
- PostgreSQL running on port 5432
- Ollama running on port 11434 (or an Anthropic API key)

## Setup

### 1. Create the database
```sql
CREATE DATABASE docanalyzer;
```

### 2. Configure environment variables
Copy and edit:
```bash
cp src/main/resources/application.yml application-local.yml
```

Key variables:
| Variable | Default | Description |
|---|---|---|
| `DB_USERNAME` | `postgres` | PostgreSQL username |
| `DB_PASSWORD` | `postgres` | PostgreSQL password |
| `AI_PROVIDER` | `ollama` | `ollama` or `anthropic` |
| `OLLAMA_MODEL` | `llava` | Vision model for images |
| `OLLAMA_TEXT_MODEL` | `llama3` | Text model for PDFs |
| `ANTHROPIC_API_KEY` | _(empty)_ | Required if provider=anthropic |

### 3. Run
```bash
./gradlew bootRun
```

## API Endpoints

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/documents/upload` | Upload a PDF or image |
| `GET` | `/api/documents/{id}/stream` | SSE stream for analysis progress |
| `GET` | `/api/documents/{id}` | Get document + analysis result |
| `GET` | `/api/documents` | List all documents |
| `DELETE` | `/api/documents/{id}` | Delete a document |

## Analysis Pipeline

```
Upload file → Save to disk → Extract text (PDFBox / vision model)
           → Send to LLM  → Parse JSON response
           → Persist result → SSE event: DONE
```

SSE events have this shape:
```json
{
  "stage": "ANALYZING",
  "message": "Sending to AI model...",
  "progressPercent": 50
}
```

## Switching to Anthropic API
Set `AI_PROVIDER=anthropic` and provide your `ANTHROPIC_API_KEY`.
The service will automatically use Claude's vision API for images.

## Next Steps
- [ ] React frontend with drag-and-drop upload + SSE progress bar
- [ ] Swap local disk storage for Azure Blob Storage
- [ ] Add pgvector for document Q&A (RAG)
- [ ] Export analysis as PDF report
