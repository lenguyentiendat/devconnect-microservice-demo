# Phase 1 Presentation and Test Guide Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create a story-first 10–15 minute Phase 1 presentation and a complete Ubuntu test runbook for the current DevConnect system.

**Architecture:** Keep presentation narrative and operational testing in separate Markdown files. Use Mermaid for system, sequence and test flows; link both deliverables from the documentation index.

**Tech Stack:** Markdown, Mermaid, Bash, Docker Compose, cURL, jq, PostgreSQL psql, Cassandra cqlsh, Kafka CLI and Maven.

## Global Constraints

- Audience is a developer team; presentation duration is 10–15 minutes.
- Use case narrative is create-post from validation through search and notification.
- User Service host port is `3000`; its Compose internal port is `8081`.
- Feed, Search and Notification host ports are `8082`, `8083` and `8084`.
- PostgreSQL owns user data; Cassandra owns post data.
- Search and Notification read models remain in memory and are lost on service restart.
- Kafka topic is `post-events`; groups are `search-service-group` and `notification-service-group`.
- Async outcome checks use bounded polling rather than fixed sleeps.

---

### Task 1: Create the Phase 1 presentation

**Files:**
- Create: `docs/PHASE1-PRESENTATION.md`
- Reference: `docs/ARCHITECTURE.md`
- Reference: `docs/EVENTS.md`

**Interfaces:**
- Consumes: current architecture, endpoints, storage ownership and event semantics.
- Produces: a self-contained 10–15 minute presentation script linked by Task 3.

- [ ] **Step 1: Verify the deliverable does not exist**

```powershell
if (Test-Path docs/PHASE1-PRESENTATION.md) { throw 'Presentation already exists' }
```

Expected: exit code 0 because the file is absent.

- [ ] **Step 2: Write the story-first presentation**

Create a Markdown document with these exact sections:

```text
Preparation
Timeline
1. Problem and Phase 1 scope
2. System architecture
3. Create-post sequence
4. Data ownership and consistency
5. Live demo
6. Phase 1 result and Phase 2 direction
Question prompts
```

Include three Mermaid diagrams:

```text
flowchart LR      - system context and dependencies
sequenceDiagram  - create-post success and error paths
flowchart TD      - demo/test order
```

Each numbered section must contain `Nói`, `Nhấn mạnh` and a time budget.

- [ ] **Step 3: Verify required presentation content**

```powershell
$content = Get-Content -Raw docs/PHASE1-PRESENTATION.md
@('```mermaid','sequenceDiagram','localhost:3000','user-service:8081','post-events','Phase 2') | ForEach-Object {
  if ($content -notmatch [regex]::Escape($_)) { throw "Missing presentation content: $_" }
}
```

Expected: exit code 0.

---

### Task 2: Create the complete test runbook

**Files:**
- Create: `docs/PHASE1-TEST-GUIDE.md`
- Reference: `docker-compose.yml`
- Reference: `docs/API.md`
- Reference: `docs/DATABASE.md`
- Reference: `docs/EVENTS.md`

**Interfaces:**
- Consumes: Compose service names, public ports, database schemas and Kafka contract.
- Produces: copy/paste Ubuntu commands and explicit expected results.

- [ ] **Step 1: Verify the runbook does not exist**

```powershell
if (Test-Path docs/PHASE1-TEST-GUIDE.md) { throw 'Test guide already exists' }
```

Expected: exit code 0 because the file is absent.

- [ ] **Step 2: Write the test runbook**

Create a Markdown document with these exact test phases:

```text
0. Test prerequisites and variables
1. Source and Compose validation
2. Infrastructure and container status
3. PostgreSQL and Flyway
4. User Service positive/negative cases
5. Feed validation cases
6. Create-post happy path
7. Feed API and Cassandra verification
8. Kafka event and consumer lag
9. Search verification with bounded polling
10. Notification verification with bounded polling
11. Persistence and in-memory limitations
12. Maven automated tests
13. Final pass/fail checklist
14. Fast troubleshooting
```

Use `RUN_ID`, `CONTENT` and `POST_ID` variables so one created post can be traced across Feed, Cassandra, Kafka, Search and Notification.

- [ ] **Step 3: Verify coverage and identifiers**

```powershell
$content = Get-Content -Raw docs/PHASE1-TEST-GUIDE.md
@('3000:8081','u001','u003','posts_by_feed','posts_by_id','post-events','search-service-group','notification-service-group','mvn test','POST_ID') | ForEach-Object {
  if ($content -notmatch [regex]::Escape($_)) { throw "Missing test coverage: $_" }
}
```

Expected: exit code 0.

---

### Task 3: Integrate and verify documentation

**Files:**
- Modify: `docs/README.md`
- Verify: `docs/PHASE1-PRESENTATION.md`
- Verify: `docs/PHASE1-TEST-GUIDE.md`

**Interfaces:**
- Consumes: deliverables from Tasks 1 and 2.
- Produces: discoverable and internally consistent documentation.

- [ ] **Step 1: Add documentation index links**

Add these entries to the appropriate reading paths and documentation map:

```markdown
- [Phase 1 presentation](PHASE1-PRESENTATION.md)
- [Phase 1 test guide](PHASE1-TEST-GUIDE.md)
```

- [ ] **Step 2: Validate Markdown links and fences**

Scan every repository Markdown file, ensure every local Markdown link resolves and each file has an even number of triple-backtick fence markers.

Expected: no broken links or unclosed code fences.

- [ ] **Step 3: Validate documented values against source**

```powershell
rg -n '3000:8081|user-service:8081|post-events|search-service-group|notification-service-group' docker-compose.yml docs/PHASE1-*.md
```

Expected: public mapping, internal URL, topic and both group IDs are present and consistent.

- [ ] **Step 4: Run whitespace validation**

```powershell
git diff --check
```

Expected: exit code 0.

- [ ] **Step 5: Review final deliverables**

```powershell
git diff -- docs/README.md docs/PHASE1-PRESENTATION.md docs/PHASE1-TEST-GUIDE.md
```

Expected: presentation, runbook and index changes only for this documentation task.

