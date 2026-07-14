# User Service Host Port 3000 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish User Service on host port `3000` while preserving container port `8081` and all internal service communication.

**Architecture:** Docker Compose changes only the host-to-container mapping from `8081:8081` to `3000:8081`. Feed Service, the User Service process and its container healthcheck continue using port `8081`; documentation distinguishes host and container addresses.

**Tech Stack:** Docker Compose, Spring Boot, Markdown, Maven.

## Global Constraints

- Host clients use `http://localhost:3000` for User Service.
- User Service continues listening on container port `8081`.
- Feed Service continues calling `http://user-service:8081`.
- User Service healthcheck continues checking `127.0.0.1:8081`.
- Direct host development retains the existing `USER_SERVICE_PORT=8081` default unless explicitly overridden.

---

### Task 1: Change the Compose host mapping

**Files:**
- Modify: `docker-compose.yml`
- Test: inline Compose mapping assertion

**Interfaces:**
- Consumes: User Service container listener on `8081`.
- Produces: host endpoint `http://localhost:3000`.

- [x] **Step 1: Run the pre-change assertion and verify it fails**

```powershell
$compose = Get-Content -Raw docker-compose.yml
if ($compose -notmatch '"3000:8081"') { throw 'User Service host port is not 3000' }
```

Expected: FAIL with `User Service host port is not 3000`.

- [x] **Step 2: Implement the minimal mapping change**

Change only the User Service published port:

```yaml
ports:
  - "3000:8081"
```

- [x] **Step 3: Verify host and internal mappings**

```powershell
$compose = Get-Content -Raw docker-compose.yml
if ($compose -notmatch '"3000:8081"') { throw 'Missing host mapping' }
if ($compose -notmatch '127\.0\.0\.1/8081') { throw 'Healthcheck port changed unexpectedly' }
if ($compose -notmatch 'http://user-service:8081') { throw 'Internal URL changed unexpectedly' }
```

Expected: exit code 0.

---

### Task 2: Update host-facing documentation

**Files:**
- Modify: `README.md`
- Modify: `docs/API.md`
- Modify: `docs/DEVELOPMENT.md`
- Modify: `docs/DOCKER.md`
- Verify unchanged internal reference: `docs/ARCHITECTURE.md`

**Interfaces:**
- Consumes: Compose host endpoint `http://localhost:3000`.
- Produces: accurate quick-start, API, development and Docker instructions.

- [x] **Step 1: Capture stale host-facing references**

```powershell
rg -n "localhost:8081|Port host.*8081|ports 5432, 8081" README.md docs
```

Expected: matches in the existing documentation.

- [x] **Step 2: Update documentation**

Apply these exact rules:

- Replace Compose/host API URLs `http://localhost:8081` with `http://localhost:3000`.
- Describe User Service as host `3000`, container `8081`.
- Replace Docker port-conflict checks for User Service from `8081` to `3000`.
- Keep direct-host defaults `USER_SERVICE_PORT=8081` and `USER_SERVICE_BASE_URL=http://localhost:8081` in the configuration reference.
- Keep Compose internal URL `http://user-service:8081`.
- Explain that direct-host users can set `USER_SERVICE_PORT=3000` and point Feed Service at `http://localhost:3000`.

- [x] **Step 3: Verify stale references are gone from Compose-facing instructions**

```powershell
rg -n "localhost:8081" README.md docs/API.md docs/DOCKER.md
```

Expected: no matches.

- [x] **Step 4: Verify intentional internal/direct-host references remain**

```powershell
rg -n "user-service:8081|USER_SERVICE_PORT.*8081|USER_SERVICE_BASE_URL.*localhost:8081" docker-compose.yml docs/ARCHITECTURE.md docs/DEVELOPMENT.md
```

Expected: internal Compose and direct-host defaults are present.

---

### Task 3: Regression verification

**Files:**
- Verify: all modified files

**Interfaces:**
- Consumes: Tasks 1 and 2.
- Produces: a verified Compose/documentation change with no Java regression.

- [x] **Step 1: Validate Markdown links, code fences and whitespace**

Run the repository Markdown validation and:

```powershell
git diff --check
```

Expected: no broken local links, unclosed fences or whitespace errors.

- [x] **Step 2: Run all Java tests**

```powershell
mvn test
```

Expected: reactor `BUILD SUCCESS`, 11 tests, 0 failures and 0 errors.

- [x] **Step 3: Review the final diff**

```powershell
git diff -- docker-compose.yml README.md docs/API.md docs/DEVELOPMENT.md docs/DOCKER.md
```

Expected: only the host mapping and related documentation changed; healthcheck and internal URL remain on `8081`.
