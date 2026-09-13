# Implementation Plan: helix-cortex — Jakarta EE 10 Enterprise API Layer

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Project Name:** helix-cortex (from "cerebral cortex" — the intelligent outer layer that wraps and exposes the helix engine core to the outside world)

**Timeline:** 4 Sprints (7 days total)
**Start Date:** TBD
**Team Size:** 1 Developer

---

## Project Overview

helix-cortex is a Jakarta EE 10 REST microservice that wraps `helix-jvm-engine` as its execution core. It exposes JVM rule compilation, full JAR bytecode analysis, and live JVM telemetry over a secured REST API. Engineers upload JAR files and JSON rules, the helix engine compiles them to bytecode, results are persisted with JPA 3.1 in PostgreSQL, and real-time JVM metrics stream to connected clients via Server-Sent Events. The entire API surface is protected with stateless JWT authentication and two-role RBAC (ENGINEER, ADMIN).

---

## Sprint Structure

Each sprint produces working, tested, deployable software:

- **Sprint 1 (Days 1-2):** Project bootstrap, Maven setup, helix integration, JWT authentication, security filter
- **Sprint 2 (Days 3-4):** Rule compile/execute pipeline, JPA entities, Criteria API analytics
- **Sprint 3 (Days 5-6):** JAR analysis engine, async concurrency, CDI events, SSE telemetry
- **Sprint 4 (Day 7):** Performance optimization, HikariCP tuning, N+1 elimination, Docker packaging

---

## Milestones & Sprints

### Sprint 1 (Days 1-2): Foundation, Security & Authentication
**Milestone:** M1 - Project Bootstrap, helix Integration, JWT Auth
**Goal:** Maven WAR project builds, helix installed as local Maven dep, `POST /api/v1/auth/login` issues JWT, `JwtSecurityFilter` rejects unauthenticated requests with 401/403.

### Sprint 2 (Days 3-4): Rule Engine REST Pipeline & JPA Persistence
**Milestone:** M2 - Rule Compilation, Execution, and Analytical Logging
**Goal:** Engineers compile JSON rules, execute them with variable contexts, results are stored in PostgreSQL with JPA 3.1, and the Criteria API supports high-density opcode session queries.

### Sprint 3 (Days 5-6): JAR Analysis, Async Concurrency & SSE Telemetry
**Milestone:** M3 - Async JAR Analysis, CDI Events, Live JVM Streaming
**Goal:** JAR file upload triggers async ManagedExecutorService analysis, antipatterns fire @ObservesAsync CDI events, ADMIN streams live JVM MXBeans via SSE every 1 second.

### Sprint 4 (Day 7): Performance Tuning, Docker & Portfolio Finalization
**Milestone:** M4 - Production Readiness & Portfolio Showcase
**Goal:** N+1 eliminated via EntityGraph/JOIN FETCH, HikariCP pool sized with formula, Docker Compose starts the full stack in one command, Postman collection and README published.

---


## Sprint 1: Foundation, Security & Authentication (Days 1-2)

**Milestone:** M1 - Project Bootstrap, helix Integration, JWT Auth
**Duration:** 10-12 hours
**Dependencies:** helix-jvm-engine built and installed locally

### Tasks

#### Task 1.1: Install helix-jvm-engine to Local Maven Repository
**Priority:** Critical
**Estimated Time:** 0.5 hours
**Labels:** `setup`, `maven`, `helix`

**Description:**
Build the helix-jvm-engine project and install its artifacts into the local Maven repository so that helix-cortex can declare them as dependencies.

**Acceptance Criteria:**
- [ ] `mvn clean install -DskipTests` completes successfully in the helix-jvm-engine directory
- [ ] `~/.m2/repository/com/helix/engine-api/1.0.0-SNAPSHOT/engine-api-1.0.0-SNAPSHOT.jar` exists
- [ ] `~/.m2/repository/com/helix/engine-core/1.0.0-SNAPSHOT/engine-core-1.0.0-SNAPSHOT.jar` exists
- [ ] No build errors in any helix module

**Commands:**
```bash
cd /path/to/helix-jvm-engine
mvn clean install -DskipTests
ls ~/.m2/repository/com/helix/
# Expected output: engine-api  engine-core  engine-profiler  engine-agent  engine-experiments  helix-parent
```

---

#### Task 1.2: Initialize helix-cortex Maven WAR Project
**Priority:** Critical
**Estimated Time:** 1.5 hours
**Labels:** `setup`, `maven`, `jakarta-ee`

**Description:**
Create the helix-cortex Maven project with the correct WAR packaging, Jakarta EE 10 BOM, WildFly 31 Maven plugin, and all required dependencies.

**Acceptance Criteria:**
- [ ] `pom.xml` at project root uses `<packaging>war</packaging>`
- [ ] Jakarta EE 10.0.0 BOM imported in `<dependencyManagement>`
- [ ] `jakarta.jakartaee-api:10.0.0` declared with `<scope>provided</scope>`
- [ ] `com.helix:engine-core:1.0.0-SNAPSHOT` and `com.helix:engine-api:1.0.0-SNAPSHOT` declared with `<scope>compile</scope>`
- [ ] WildFly Maven Plugin 4.x configured for deployment
- [ ] `mvn clean package` produces `target/helix-cortex.war`
- [ ] Java source/target set to 17

**Files to Create:**
```
helix-cortex/
├── pom.xml
└── src/
    └── main/
        ├── java/
        │   └── com/pulse/
        │       └── app/
        │           └── CortexApplication.java
        └── webapp/
            └── WEB-INF/
                └── web.xml
```

**Technical Notes:**
- `CortexApplication` extends `jakarta.ws.rs.core.Application` and is annotated `@ApplicationPath("/api/v1")`
- `web.xml` is minimal — only required by WildFly for WAR recognition

---

#### Task 1.3: Configure persistence.xml and DataSource
**Priority:** Critical
**Estimated Time:** 1 hour
**Labels:** `jpa`, `datasource`, `configuration`

**Description:**
Define the JPA persistence unit referencing the JNDI datasource, PostgreSQL dialect, HikariCP settings, and schema generation properties.

**Acceptance Criteria:**
- [ ] `persistence.xml` located at `src/main/resources/META-INF/persistence.xml`
- [ ] `transaction-type="JTA"` set
- [ ] `<jta-data-source>java:jboss/datasources/CortexDS</jta-data-source>` set
- [ ] `shared-cache-mode` set to `ENABLE_SELECTIVE`
- [ ] `hibernate.dialect` set to `org.hibernate.dialect.PostgreSQLDialect`
- [ ] `hibernate.hbm2ddl.auto` set to `create-drop` for development
- [ ] `hibernate.generate_statistics` set to `true` for Sprint 4 profiling
- [ ] `hibernate.hikari.maximumPoolSize` set to `16`
- [ ] `hibernate.hikari.connectionTimeout` set to `3000`
- [ ] `hibernate.hikari.maxLifetime` set to `1800000`

**Files to Create:**
```
src/main/resources/META-INF/
└── persistence.xml
```

---

#### Task 1.4: Create EngineerAccount JPA Entity
**Priority:** Critical
**Estimated Time:** 1 hour
**Labels:** `jpa`, `entity`, `security`

**Description:**
Create the `EngineerAccount` entity that stores engineer credentials. Passwords are stored as PBKDF2WithHmacSHA256 hashes. The entity uses optimistic locking.

**Acceptance Criteria:**
- [ ] `EngineerAccount` annotated with `@Entity`, `@Table(name="engineer_account")`
- [ ] `id` field: `@Id @GeneratedValue(strategy = GenerationType.IDENTITY)`
- [ ] `username` field: `@Column(nullable = false, unique = true, length = 100)`
- [ ] `passwordHash` field: `@Column(name = "password_hash", nullable = false, length = 255)`
- [ ] `role` field: `@Enumerated(EnumType.STRING)` using `EngineRole` enum with values `ENGINEER` and `ADMIN`
- [ ] `version` field: `@Version private Long version`
- [ ] `createdAt` field: `@Column(name = "created_at") private Instant createdAt`
- [ ] `@Cacheable(false)` — security-sensitive, never cache
- [ ] `verifyPassword(String raw)` method returns `boolean` using `Pbkdf2PasswordHash`

**Files to Create:**
```
src/main/java/com/pulse/entity/
├── EngineerAccount.java
└── EngineRole.java
```

**Test Cases:**
- Verify `@Column(unique=true)` constraint is present on username
- Verify `@Version` field type is `Long`
- Verify `EngineRole` enum has exactly two values: `ENGINEER` and `ADMIN`

---

#### Task 1.5: Create TokenService for HMAC-SHA256 JWT
**Priority:** Critical
**Estimated Time:** 2 hours
**Labels:** `security`, `jwt`, `control`

**Description:**
Implement stateless JWT creation and verification using manual HMAC-SHA256 signing with `javax.crypto.Mac`. No external JWT library. Token payload contains `sub` (username), `role`, `iat`, and `exp` claims.

**Acceptance Criteria:**
- [ ] `TokenService` is `@ApplicationScoped`
- [ ] `JWT_SECRET` injected via `@ConfigProperty(name="jwt.secret")`
- [ ] `issue(String subject, EngineRole role)` returns a signed JWT string in `header.payload.signature` format
- [ ] `verify(String token)` returns `TokenClaims` record or throws `InvalidTokenException` on signature mismatch
- [ ] `verify` throws `TokenExpiredException` when `exp` claim is in the past
- [ ] JWT expiry is 3600 seconds
- [ ] `TokenClaims` is a Java record with fields: `subject`, `role`, `issuedAt`, `expiresAt`
- [ ] `InvalidTokenException` and `TokenExpiredException` are unchecked exceptions
- [ ] Signature uses `MessageDigest.isEqual()` for constant-time comparison

**Files to Create:**
```
src/main/java/com/pulse/control/
└── TokenService.java

src/main/java/com/pulse/boundary/filter/
├── TokenClaims.java
├── InvalidTokenException.java
└── TokenExpiredException.java
```

**Test Cases:**
- Issue a token and verify it returns `TokenClaims` with the correct subject and role
- Tamper with the payload section and verify `InvalidTokenException` is thrown
- Construct a token with `exp` in the past and verify `TokenExpiredException` is thrown
- Verify two calls to `issue()` with the same subject produce identical payload sections

---

#### Task 1.6: Create EngineerAccountRepository
**Priority:** Critical
**Estimated Time:** 1 hour
**Labels:** `jpa`, `repository`

**Description:**
Implement the JPA repository for `EngineerAccount` using named JPQL parameters. All queries use `:param` named parameters — no string concatenation.

**Acceptance Criteria:**
- [ ] `EngineerAccountRepository` is `@ApplicationScoped`
- [ ] `@PersistenceContext EntityManager em` injected
- [ ] `findByUsername(String username)` uses `SELECT a FROM EngineerAccount a WHERE a.username = :username` and returns `Optional<EngineerAccount>`
- [ ] `save(EngineerAccount account)` calls `em.persist(account)` and returns the managed entity
- [ ] `existsByUsername(String username)` returns `boolean`
- [ ] No raw SQL string concatenation in any method

**Files to Create:**
```
src/main/java/com/pulse/control/
└── EngineerAccountRepository.java
```

**Test Cases:**
- Save an `EngineerAccount` and find it by username — result is non-empty
- Find a non-existent username — result is `Optional.empty()`
- Verify `existsByUsername` returns `true` for a saved account and `false` for a missing username
- Verify injection payload `"admin' OR '1'='1"` as username returns empty (parameterized query)

---

#### Task 1.7: Create @Secured NameBinding Annotation and JwtSecurityFilter
**Priority:** Critical
**Estimated Time:** 1.5 hours
**Labels:** `security`, `filter`, `jax-rs`

**Description:**
Create the `@Secured` JAX-RS `@NameBinding` annotation and implement `JwtSecurityFilter` that intercepts all `@Secured` endpoints. The filter validates the Bearer token, sets the `SecurityContext`, and writes an audit log entry.

**Acceptance Criteria:**
- [ ] `@Secured` is a `@NameBinding`, `@Retention(RUNTIME)`, `@Target({TYPE, METHOD})` annotation
- [ ] `JwtSecurityFilter` is annotated `@Secured`, `@Provider`, `@Priority(Priorities.AUTHENTICATION)`
- [ ] Filter implements `ContainerRequestFilter`
- [ ] Missing or malformed `Authorization` header → `abortWith(Response.status(401))`
- [ ] Failed `TokenService.verify()` → `abortWith(Response.status(403))`
- [ ] Successful verification → `ctx.setSecurityContext(new JwtSecurityContext(claims))`
- [ ] `JwtSecurityContext` implements `SecurityContext` with `getUserPrincipal()` returning the subject and `isUserInRole(String role)` comparing against the token's role claim
- [ ] All 401/403 responses include `Content-Type: application/problem+json`
- [ ] `AuditLogRepository.logAsync(subject, path)` called after successful verification

**Files to Create:**
```
src/main/java/com/pulse/boundary/filter/
├── Secured.java
├── JwtSecurityFilter.java
└── JwtSecurityContext.java
```

**Test Cases:**
- Request with no `Authorization` header → response status 401
- Request with `Authorization: Bearer invalid.token.here` → response status 403
- Request with a valid token but expired → response status 401
- Request with valid unexpired token → filter passes through and `SecurityContext` is set with correct principal

---

#### Task 1.8: Create AuthResource
**Priority:** Critical
**Estimated Time:** 1.5 hours
**Labels:** `boundary`, `auth`, `jax-rs`

**Description:**
Implement `AuthResource` with `POST /api/v1/auth/login` and `POST /api/v1/auth/register`. Login validates credentials and returns a JWT. Register creates a new ENGINEER account with PBKDF2-hashed password.

**Acceptance Criteria:**
- [ ] `AuthResource` annotated `@Path("/auth")`, `@Consumes(APPLICATION_JSON)`, `@Produces(APPLICATION_JSON)`
- [ ] No `@Secured` annotation — public endpoints
- [ ] `POST /login` receives `LoginRequest` record with `username` and `password`
- [ ] `POST /login` returns `LoginResponse` record with `token`, `role`, and `expiresIn` (3600)
- [ ] `POST /login` returns 401 if username not found or password does not match
- [ ] `POST /register` receives `RegisterRequest` record with `username` and `password`
- [ ] `POST /register` hashes the password using `Pbkdf2PasswordHash` before persisting
- [ ] `POST /register` returns 409 if the username already exists
- [ ] `POST /register` returns 201 with the created account's username

**Files to Create:**
```
src/main/java/com/pulse/boundary/
└── AuthResource.java

src/main/java/com/pulse/boundary/dto/
├── LoginRequest.java
├── LoginResponse.java
└── RegisterRequest.java
```

**Test Cases:**
- `POST /login` with valid credentials → 200 with a non-null `token` field
- `POST /login` with wrong password → 401
- `POST /login` with unknown username → 401
- `POST /register` with a new username → 201
- `POST /register` with a duplicate username → 409

---

#### Task 1.9: Create AuditLog Entity and Repository
**Priority:** High
**Estimated Time:** 1 hour
**Labels:** `jpa`, `entity`, `audit`

**Description:**
Create the `AuditLog` entity for recording every authenticated API call. The repository writes asynchronously so it does not block the request thread.

**Acceptance Criteria:**
- [ ] `AuditLog` annotated `@Entity`, `@Table(name="audit_log")`
- [ ] `@Cacheable(false)` — audit data is write-heavy and must never be stale
- [ ] Fields: `id` (IDENTITY), `engineerId` (VARCHAR 100), `endpoint` (VARCHAR 255), `httpMethod` (VARCHAR 10), `timestamp` (Instant), `success` (boolean)
- [ ] `@Index(name="idx_audit_engineer", columnList="engineer_id")` declared on `@Table`
- [ ] `AuditLogRepository.logAsync(String engineerId, String endpoint)` annotated `@Asynchronous` (Jakarta EJB 4.0) so it returns immediately
- [ ] The async method is annotated `@TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)` to run in its own transaction

**Files to Create:**
```
src/main/java/com/pulse/entity/
└── AuditLog.java

src/main/java/com/pulse/control/
└── AuditLogRepository.java
```

**Test Cases:**
- Save an `AuditLog` record and verify it is retrievable by `engineerId`
- Verify `AuditLog` has no `@Cacheable(true)` annotation
- Verify the `timestamp` field is populated automatically at construction

---

#### Task 1.10: Seed Data SQL Script and Sprint 1 Verification
**Priority:** High
**Estimated Time:** 1 hour
**Labels:** `setup`, `verification`, `milestone`

**Description:**
Create the initial data SQL script that seeds the ADMIN and ENGINEER accounts on every startup, then verify the entire Sprint 1 stack works end-to-end.

**Acceptance Criteria:**
- [ ] `initial-data.sql` placed at `src/main/resources/META-INF/initial-data.sql`
- [ ] Script inserts one ADMIN account (username: `admin`) and one ENGINEER account (username: `engineer_1`)
- [ ] `jakarta.persistence.sql-load-script-source` property references this file in `persistence.xml`
- [ ] `POST /api/v1/auth/login` with `{"username":"admin","password":"admin123"}` returns 200 and a JWT
- [ ] `GET /api/v1/rules/sessions` without token returns 401
- [ ] `GET /api/v1/rules/sessions` with expired token returns 401
- [ ] `GET /api/v1/rules/sessions` with valid ENGINEER token returns 200 (empty list is acceptable)
- [ ] `mvn clean package wildfly:deploy` deploys successfully on local WildFly 31

**Commands:**
```bash
mvn clean package wildfly:deploy
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
# Expected: {"token":"eyJ...","role":"ADMIN","expiresIn":3600}
```

---

### Sprint 1 Summary

**Total Tasks:** 10
**Total Estimated Time:** 12 hours
**Critical Path:** Task 1.1 → Task 1.2 → Task 1.3 → Task 1.5 → Task 1.7 → Task 1.8

**Deliverables:**
- Complete Maven WAR project with Jakarta EE 10 and helix-engine dependencies
- JWT authentication (issue + verify) with HMAC-SHA256
- PBKDF2 password hashing for engineer accounts
- @Secured JAX-RS filter with 401/403 enforcement
- Auth endpoints (login, register) tested and deployed

**End-of-Sprint Checklist:**
- [ ] All 10 tasks completed
- [ ] `mvn clean package` produces a deployable WAR
- [ ] `POST /api/v1/auth/login` returns a JWT
- [ ] Unauthenticated requests to secured endpoints return 401
- [ ] All unit tests pass
- [ ] Code committed to Git with message `feat(sprint-1): foundation, security and JWT auth`

---


## Sprint 2: Rule Engine REST Pipeline & JPA Persistence (Days 3-4)

**Milestone:** M2 - Rule Compilation, Execution, and Analytical Logging
**Duration:** 10-12 hours
**Dependencies:** Sprint 1 completed

### Tasks

#### Task 2.1: Create HelixProducer CDI Bean
**Priority:** Critical
**Estimated Time:** 1 hour
**Labels:** `cdi`, `helix`, `control`

**Description:**
Create a CDI `@Produces` class that instantiates the helix `RuleEngine` and `Profiler` from the helix API and makes them injectable throughout the application scope.

**Acceptance Criteria:**
- [ ] `HelixProducer` is `@ApplicationScoped`
- [ ] `@Produces @ApplicationScoped RuleEngine produceRuleEngine()` calls `HelixApplication.createEngine()` and returns the result
- [ ] `@Produces @ApplicationScoped Profiler produceProfiler(RuleEngine engine)` calls `HelixApplication.createProfiler(engine)` and returns the result
- [ ] Both produced beans are disposable — `@Disposes` methods call `.stop()` on the `Profiler`
- [ ] `@Inject RuleEngine ruleEngine` resolves correctly in a CDI context

**Files to Create:**
```
src/main/java/com/pulse/control/
└── HelixProducer.java
```

**Test Cases:**
- Injecting `RuleEngine` into a CDI bean resolves to a non-null instance
- Calling `ruleEngine.compile(rule)` on a simple test rule does not throw
- Injecting `Profiler` resolves to a non-null instance with `isRunning()` returning false initially

---

#### Task 2.2: Create RuleSession and OpcodeMetric JPA Entities
**Priority:** Critical
**Estimated Time:** 1.5 hours
**Labels:** `jpa`, `entity`

**Description:**
Create the two JPA entities that persist rule compilation and execution results. `RuleSession` holds the submitted rule JSON and the helix-assigned compiled rule ID. `OpcodeMetric` holds per-session analysis metrics and is cascaded from `RuleSession`.

**Acceptance Criteria:**
- [ ] `RuleSession` annotated `@Entity`, `@Table(name="rule_session")`
- [ ] `@Cacheable(true)` — sessions are read-heavy for the ADMIN dashboard
- [ ] `RuleSession` fields: `id` (IDENTITY), `engineerId` (VARCHAR 100), `ruleJson` (TEXT), `compiledRuleId` (VARCHAR 255), `status` (enum: `COMPILED`, `EXECUTED`, `FAILED`), `version` (`@Version Long`), `createdAt` (Instant)
- [ ] `@Index(name="idx_rule_session_engineer", columnList="engineer_id")` on `@Table`
- [ ] `OpcodeMetric` annotated `@Entity`, `@Table(name="opcode_metric")`
- [ ] `OpcodeMetric` fields: `id` (IDENTITY), `session` (`@ManyToOne` to `RuleSession`), `totalOpcodeCount` (long), `executionTimeNanos` (long), `antipatternFlag` (boolean), `evaluatedAt` (Instant)
- [ ] `RuleSession.metrics` declared `@OneToMany(mappedBy="session", cascade=CascadeType.ALL, orphanRemoval=true)` with `@BatchSize(size=10)`

**Files to Create:**
```
src/main/java/com/pulse/entity/
├── RuleSession.java
├── OpcodeMetric.java
└── SessionStatus.java
```

**Test Cases:**
- Persist a `RuleSession` and verify its `id` is populated
- Persist a `RuleSession` with two `OpcodeMetric` children; reload and verify `metrics.size() == 2`
- Verify `@Version` field increments on update
- Verify `@Cacheable(true)` is present on `RuleSession`

---

#### Task 2.3: Create RuleSessionRepository with Criteria API
**Priority:** Critical
**Estimated Time:** 2 hours
**Labels:** `jpa`, `repository`, `criteria-api`

**Description:**
Implement the repository for `RuleSession` with parameterized JPQL queries and a Criteria API query for high-opcode-density session analytics.

**Acceptance Criteria:**
- [ ] `RuleSessionRepository` is `@ApplicationScoped`
- [ ] `findAll()` returns `List<RuleSession>` using `SELECT s FROM RuleSession s`
- [ ] `findByEngineerId(String engineerId)` uses `:engineerId` named parameter
- [ ] `findHighOpcodeDensitySessions(long threshold)` uses Criteria API: joins `RuleSession` to `OpcodeMetric`, filters `WHERE m.totalOpcodeCount > :threshold`, orders by `totalOpcodeCount DESC`
- [ ] `findHighOpcodeDensitySessions` applies an `EntityGraph` hint `jakarta.persistence.fetchgraph` to prevent N+1 when loading `metrics`
- [ ] `save(RuleSession session)` calls `em.persist(session)` and returns the managed entity
- [ ] `updateStatus(Long id, SessionStatus status)` uses `em.find()` then sets status within the active transaction

**Files to Create:**
```
src/main/java/com/pulse/control/
└── RuleSessionRepository.java
```

**Test Cases:**
- `findAll()` on an empty database returns an empty list
- `findByEngineerId("engineer_1")` returns only sessions belonging to `engineer_1`
- `findHighOpcodeDensitySessions(500L)` returns sessions whose `OpcodeMetric.totalOpcodeCount` exceeds 500
- Verify `findHighOpcodeDensitySessions` executes exactly 1 SQL statement (check Hibernate statistics)
- Verify `findByEngineerId("' OR 1=1 --")` returns empty (parameterized query)

---

#### Task 2.4: Create RuleSessionControl
**Priority:** Critical
**Estimated Time:** 2 hours
**Labels:** `control`, `helix`, `jpa`, `jta`

**Description:**
Implement the CDI control bean that bridges JAX-RS requests to helix `RuleEngine` operations and persists results via JPA. All persistence operations run within the caller's JTA transaction.

**Acceptance Criteria:**
- [ ] `RuleSessionControl` is `@ApplicationScoped`
- [ ] `@Transactional(TxType.MANDATORY)` applied at class level — it must always be called within an active transaction
- [ ] `@Inject RuleEngine ruleEngine` uses the helix instance produced by `HelixProducer`
- [ ] `compileAndSave(RuleRequest req, String engineerId)` calls `ruleEngine.compile(rule)`, constructs a `RuleSession` with status `COMPILED`, persists it, and returns the session
- [ ] `executeAndSave(Long sessionId, Map<String,Object> variables)` loads the session, constructs an `ExecutionContext`, calls `ruleEngine.execute(compiled, ctx)`, constructs an `OpcodeMetric` from the result, updates session status to `EXECUTED`, and returns the `OpcodeMetric`
- [ ] `executeAndSave` sets session status to `FAILED` if `ExecutionResult.isSuccess()` is `false`
- [ ] `RuleRequest` is a record with `ruleName`, `ruleVersion`, `expression`, and `inputSchema` (Map<String,String>)

**Files to Create:**
```
src/main/java/com/pulse/control/
└── RuleSessionControl.java

src/main/java/com/pulse/boundary/dto/
├── RuleRequest.java
└── ExecutionRequest.java
```

**Test Cases:**
- `compileAndSave` with a valid expression returns a `RuleSession` with `status == COMPILED`
- `executeAndSave` with `{x: 15}` on a compiled `return x > 10` rule returns an `OpcodeMetric` with `executionTimeNanos > 0`
- `executeAndSave` with a deliberately invalid variable type sets session status to `FAILED`
- Verify `compileAndSave` called without an active transaction throws `javax.ejb.EJBTransactionRequiredException`

---

#### Task 2.5: Create RuleResource
**Priority:** Critical
**Estimated Time:** 2 hours
**Labels:** `boundary`, `jax-rs`

**Description:**
Implement the JAX-RS resource for rule compilation and execution. All endpoints are `@Secured`. ADMIN can view all sessions; ENGINEER can only view their own.

**Acceptance Criteria:**
- [ ] `RuleResource` is `@Path("/rules")`, `@Secured`, `@Produces(APPLICATION_JSON)`, `@Consumes(APPLICATION_JSON)`
- [ ] `@Inject RuleSessionControl control` and `@Inject RuleSessionRepository repo` present
- [ ] `POST /rules/compile` annotated `@RolesAllowed({"ENGINEER","ADMIN"})` — calls `control.compileAndSave()` and returns 201 with the session as body
- [ ] `POST /rules/execute/{sessionId}` annotated `@RolesAllowed({"ENGINEER","ADMIN"})` — calls `control.executeAndSave()` and returns 200 with the `OpcodeMetric`
- [ ] `GET /rules/sessions` annotated `@RolesAllowed("ADMIN")` — calls `repo.findAll()` and returns 200
- [ ] `GET /rules/sessions/mine` annotated `@RolesAllowed({"ENGINEER","ADMIN"})` — reads subject from `SecurityContext`, calls `repo.findByEngineerId()`, returns 200
- [ ] `GET /rules/sessions/high-density` annotated `@RolesAllowed("ADMIN")` — accepts `@QueryParam("threshold") long threshold`, calls `repo.findHighOpcodeDensitySessions(threshold)`, returns 200
- [ ] All 5xx errors return `application/problem+json`

**Files to Create:**
```
src/main/java/com/pulse/boundary/
└── RuleResource.java
```

**Test Cases:**
- `POST /rules/compile` with valid `RuleRequest` and ENGINEER JWT → 201 with session id
- `POST /rules/compile` with no token → 401
- `GET /rules/sessions` with ADMIN token → 200
- `GET /rules/sessions` with ENGINEER token → 403
- `GET /rules/sessions/mine` with ENGINEER token → 200 with only that engineer's sessions
- `POST /rules/execute/{sessionId}` where sessionId does not exist → 404

---

#### Task 2.6: Create GlobalExceptionMapper
**Priority:** High
**Estimated Time:** 1 hour
**Labels:** `boundary`, `error-handling`, `jax-rs`

**Description:**
Implement a JAX-RS `ExceptionMapper` that maps common exceptions to RFC 7807 `application/problem+json` responses.

**Acceptance Criteria:**
- [ ] `GlobalExceptionMapper` implements `ExceptionMapper<Exception>` and is annotated `@Provider`
- [ ] `WebApplicationException` is mapped to its own HTTP status code
- [ ] `RuleCompilationException` (from helix) is mapped to 400 Bad Request
- [ ] `RuleExecutionException` (from helix) is mapped to 422 Unprocessable Entity
- [ ] `jakarta.persistence.EntityNotFoundException` is mapped to 404
- [ ] `jakarta.persistence.OptimisticLockException` is mapped to 409 Conflict
- [ ] All responses use `Content-Type: application/problem+json`
- [ ] Response body is a `ProblemDetail` record with fields: `status`, `title`, `detail`

**Files to Create:**
```
src/main/java/com/pulse/boundary/
└── GlobalExceptionMapper.java

src/main/java/com/pulse/boundary/dto/
└── ProblemDetail.java
```

**Test Cases:**
- A `RuleCompilationException` thrown from `RuleResource` results in a 400 response with `Content-Type: application/problem+json`
- A `WebApplicationException(404)` results in a 404 response
- An `OptimisticLockException` results in a 409 response

---

#### Task 2.7: Sprint 2 Verification — Full Rule Lifecycle
**Priority:** Critical
**Estimated Time:** 1 hour
**Labels:** `verification`, `milestone`

**Description:**
Verify the complete rule compilation and execution lifecycle end-to-end using curl or Postman against a running WildFly instance.

**Acceptance Criteria:**
- [ ] Login as engineer → receive JWT
- [ ] `POST /rules/compile` with `{"ruleName":"check","ruleVersion":"1.0","expression":"return x > 10","inputSchema":{"x":"int"}}` → 201 with a session id
- [ ] `POST /rules/execute/{id}` with `{"variables":{"x":15}}` → 200 with `OpcodeMetric`
- [ ] Login as admin → `GET /rules/sessions` returns all sessions including the one just created
- [ ] `GET /rules/sessions/high-density?threshold=0` returns all sessions with any opcode count
- [ ] Database contains rows in `rule_session` and `opcode_metric` tables (verify via psql)
- [ ] All unit tests pass: `mvn test`

**Commands:**
```bash
# Compile a rule:
curl -X POST http://localhost:8080/api/v1/rules/compile \
  -H "Authorization: Bearer $ENGINEER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"ruleName":"check","ruleVersion":"1.0","expression":"return x > 10","inputSchema":{"x":"int"}}'

# Execute it (replace {id} with the returned session id):
curl -X POST http://localhost:8080/api/v1/rules/execute/{id} \
  -H "Authorization: Bearer $ENGINEER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"variables":{"x":15}}'
```

---

### Sprint 2 Summary

**Total Tasks:** 7
**Total Estimated Time:** 11.5 hours
**Critical Path:** Task 2.1 → Task 2.4 → Task 2.5 → Task 2.7

**Deliverables:**
- helix RuleEngine injectable via CDI
- Full rule compile/execute REST pipeline
- JPA entities with optimistic locking and L2 cache configuration
- Criteria API analytics query with EntityGraph N+1 prevention
- RFC 7807 problem+json error handling

**End-of-Sprint Checklist:**
- [ ] All 7 tasks completed
- [ ] Rule lifecycle (compile → execute → persist) verified end-to-end
- [ ] Database schema is correct
- [ ] All unit tests pass
- [ ] Code committed with message `feat(sprint-2): rule engine REST pipeline and JPA persistence`

---


## Sprint 3: JAR Analysis, Async Concurrency & SSE Telemetry (Days 5-6)

**Milestone:** M3 - Async JAR Analysis, CDI Events, Live JVM Streaming
**Duration:** 10-12 hours
**Dependencies:** Sprint 2 completed

### Tasks

#### Task 3.1: Create JarAnalysis JPA Entity
**Priority:** Critical
**Estimated Time:** 1 hour
**Labels:** `jpa`, `entity`

**Description:**
Create the `JarAnalysis` entity that stores the results of a full JAR file analysis including class count, total opcode count, dependency graph (as JSON), and antipattern count.

**Acceptance Criteria:**
- [ ] `JarAnalysis` annotated `@Entity`, `@Table(name="jar_analysis")`
- [ ] `@Cacheable(false)` — large payloads, write-once
- [ ] Fields: `id` (IDENTITY), `filename` (VARCHAR 255, not null), `engineerId` (VARCHAR 100), `classCount` (int), `totalOpcodes` (long), `antipatternCount` (int), `dependencyGraphJson` (TEXT), `status` (enum: `PENDING`, `COMPLETED`, `FAILED`), `version` (`@Version Long`), `submittedAt` (Instant)
- [ ] `classMetrics` declared `@OneToMany(mappedBy="jarAnalysis", cascade=CascadeType.ALL)` with `@BatchSize(size=20)`
- [ ] `AnalysisClassMetric` child entity: `id`, `className` (VARCHAR 500), `opcodeCount` (int), `hasAntipattern` (boolean), `jarAnalysis` (`@ManyToOne`)

**Files to Create:**
```
src/main/java/com/pulse/entity/
├── JarAnalysis.java
├── AnalysisClassMetric.java
└── AnalysisStatus.java
```

**Test Cases:**
- Persist a `JarAnalysis` with `status=PENDING` and verify it is retrievable
- Add `AnalysisClassMetric` children via `classMetrics.add()` and verify cascade saves them
- Verify `@Version` field is `Long`

---

#### Task 3.2: Create RuleAntipatternEvent CDI Event
**Priority:** High
**Estimated Time:** 0.5 hours
**Labels:** `cdi`, `events`

**Description:**
Create the CDI event payload record that carries antipattern detection information from `JarAnalysisControl` to `AntipatternObserver`.

**Acceptance Criteria:**
- [ ] `RuleAntipatternEvent` is a Java record
- [ ] Fields: `className` (String), `antipatternType` (String), `detectedAt` (Instant), `jarAnalysisId` (Long)
- [ ] `antipatternType` values are defined as constants in `AntipatternType` class: `STRING_CONCAT_LOOP`, `EXCESSIVE_OBJECT_CREATION`, `REDUNDANT_INSTANCEOF`

**Files to Create:**
```
src/main/java/com/pulse/control/
├── RuleAntipatternEvent.java
└── AntipatternType.java
```

---

#### Task 3.3: Create AntipatternObserver
**Priority:** High
**Estimated Time:** 1 hour
**Labels:** `cdi`, `events`, `async`

**Description:**
Implement the CDI observer that handles `RuleAntipatternEvent` asynchronously using `@ObservesAsync`. The observer writes an alert to the `AuditLog`.

**Acceptance Criteria:**
- [ ] `AntipatternObserver` is `@ApplicationScoped`
- [ ] `onAntipattern(@ObservesAsync RuleAntipatternEvent event)` method handles events asynchronously
- [ ] The handler logs the class name, antipattern type, and jar analysis id to `System.err` and also calls `auditLogRepository.logAsync()`
- [ ] `@Inject AuditLogRepository auditLogRepository` present
- [ ] The observer method is `void` and does not throw checked exceptions

**Files to Create:**
```
src/main/java/com/pulse/control/
└── AntipatternObserver.java
```

**Test Cases:**
- Fire a `RuleAntipatternEvent` synchronously in a test; verify `auditLogRepository.logAsync()` is called
- Verify the observer method signature uses `@ObservesAsync` not `@Observes`

---

#### Task 3.4: Create JarAnalysisControl with ManagedExecutorService
**Priority:** Critical
**Estimated Time:** 3 hours
**Labels:** `control`, `concurrency`, `asm`, `helix`

**Description:**
Implement the CDI control bean for JAR file analysis. The analysis is offloaded to a `ManagedExecutorService` so the JAX-RS thread returns immediately with 202 Accepted. Each class in the JAR is inspected using ASM `ClassReader` to count opcodes and detect three antipattern types. The helix `RuleEngine.executeAsync()` is used for parallel per-class rule evaluation.

**Acceptance Criteria:**
- [ ] `JarAnalysisControl` is `@ApplicationScoped`
- [ ] `@Resource ManagedExecutorService executor` injected
- [ ] `submitAsync(InputStream jarStream, String filename, String engineerId)` saves a `JarAnalysis` with `status=PENDING`, then calls `CompletableFuture.supplyAsync(() -> analyze(jarStream, jarAnalysisId), executor)`, and returns the `JarAnalysis.id` immediately
- [ ] `analyze(InputStream jarStream, Long jarAnalysisId)` private method: opens `JarInputStream`, iterates `JarEntry`, skips non-`.class` entries, calls `inspectClass(byte[])` per class
- [ ] `inspectClass(byte[] bytecode)` uses `org.objectweb.asm.ClassReader` and a custom `OpcodeCountingVisitor extends ClassVisitor` that counts total instructions in each method via a `MethodVisitor`
- [ ] Antipattern `STRING_CONCAT_LOOP`: detected when a class has more than 20 `INVOKEVIRTUAL` instructions that reference `StringBuilder.append`
- [ ] Antipattern `EXCESSIVE_OBJECT_CREATION`: detected when a class has more than 50 `NEW` opcode instructions
- [ ] Antipattern `REDUNDANT_INSTANCEOF`: detected when a class has more than 10 consecutive `INSTANCEOF` instructions
- [ ] When any antipattern is detected, fires `antipatternBus.fireAsync(new RuleAntipatternEvent(...))`
- [ ] After all classes are analyzed, updates `JarAnalysis` status to `COMPLETED` and persists summary metrics
- [ ] On any exception, updates `JarAnalysis` status to `FAILED`

**Files to Create:**
```
src/main/java/com/pulse/control/
├── JarAnalysisControl.java
└── OpcodeCountingVisitor.java
```

**Test Cases:**
- Submit a real `.jar` file (use `helix-engine-core-1.0.0-SNAPSHOT.jar`) and verify the returned id is a valid `JarAnalysis.id`
- Poll `GET /jars/sessions/{id}` until `status == COMPLETED` — verify `classCount > 0`
- Submit a JAR containing a class with 60+ `NEW` opcodes — verify `antipatternCount > 0`
- Verify the `antipatternBus.fireAsync()` is called for each antipattern-flagged class

---

#### Task 3.5: Create JarAnalysisResource
**Priority:** Critical
**Estimated Time:** 1.5 hours
**Labels:** `boundary`, `jax-rs`, `multipart`

**Description:**
Implement the JAX-RS resource for JAR file uploads. The upload endpoint accepts `multipart/form-data` and delegates immediately to `JarAnalysisControl.submitAsync()`, returning 202 Accepted with a `Location` header.

**Acceptance Criteria:**
- [ ] `JarAnalysisResource` is `@Path("/jars")`, `@Secured`, `@Produces(APPLICATION_JSON)`
- [ ] `POST /jars/analyze` annotated `@Consumes(MULTIPART_FORM_DATA)`, `@RolesAllowed({"ENGINEER","ADMIN"})`
- [ ] Method signature: `Response analyze(@FormDataParam("file") InputStream jarStream, @FormDataParam("file") FormDataContentDisposition info, @Context SecurityContext sc)`
- [ ] Returns `Response.accepted().header("Location", "/api/v1/jars/sessions/" + analysisId).build()`
- [ ] `GET /jars/sessions/{id}` annotated `@RolesAllowed({"ENGINEER","ADMIN"})` — loads `JarAnalysis` by id, returns 200 or 404
- [ ] `GET /jars/sessions` annotated `@RolesAllowed("ADMIN")` — returns all `JarAnalysis` records

**Files to Create:**
```
src/main/java/com/pulse/boundary/
└── JarAnalysisResource.java
```

**Test Cases:**
- `POST /jars/analyze` with a valid JAR file and ENGINEER token → 202 with `Location` header
- `POST /jars/analyze` with no token → 401
- `GET /jars/sessions/{id}` with a valid id → 200 with `JarAnalysis` JSON
- `GET /jars/sessions/{id}` with a non-existent id → 404
- `GET /jars/sessions` with ENGINEER token → 403

---

#### Task 3.6: Create JvmTelemetrySnapshot DTO and TelemetryControl
**Priority:** Critical
**Estimated Time:** 2.5 hours
**Labels:** `control`, `sse`, `jvm-mxbeans`, `concurrency`

**Description:**
Implement the `TelemetryControl` CDI bean that maintains a `SseBroadcaster`, samples real JVM MXBeans every 1 second on a managed thread, and broadcasts the snapshot to all connected SSE clients.

**Acceptance Criteria:**
- [ ] `JvmTelemetrySnapshot` is a Java record with fields: `heapUsedBytes` (long), `heapMaxBytes` (long), `heapUsedPercent` (double), `gcCollectionCount` (long), `gcCollectionTimeMs` (long), `threadCount` (int), `daemonThreadCount` (int), `uptimeMs` (long), `sampledAt` (Instant)
- [ ] `TelemetryControl` is `@ApplicationScoped`
- [ ] `@Context Sse sse` injected
- [ ] `@Resource ManagedExecutorService executor` injected
- [ ] `@PostConstruct void init()` creates the `SseBroadcaster` via `sse.newBroadcaster()` and registers an `onClose` handler that logs client disconnection
- [ ] `@PostConstruct` also submits a recurring sampler task using `executor.scheduleAtFixedRate(this::sampleAndBroadcast, 0, 1, TimeUnit.SECONDS)`
- [ ] `registerSink(SseEventSink sink)` calls `broadcaster.register(sink)`
- [ ] `sampleAndBroadcast()` calls `currentSnapshot()` then creates an `OutboundSseEvent` with `name("jvm-telemetry")`, `id(timestamp)`, `data(JvmTelemetrySnapshot.class, snapshot)`, and calls `broadcaster.broadcast(event)`
- [ ] `currentSnapshot()` reads from `ManagementFactory.getMemoryMXBean()`, `ManagementFactory.getGarbageCollectorMXBeans()`, `ManagementFactory.getThreadMXBean()`, and `ManagementFactory.getRuntimeMXBean()`
- [ ] `heapUsedPercent` computed as `(heapUsedBytes * 100.0) / heapMaxBytes`
- [ ] `gcCollectionCount` is the sum across all `GarbageCollectorMXBean` instances

**Files to Create:**
```
src/main/java/com/pulse/control/
└── TelemetryControl.java

src/main/java/com/pulse/boundary/dto/
└── JvmTelemetrySnapshot.java
```

**Test Cases:**
- `currentSnapshot()` returns a non-null `JvmTelemetrySnapshot` with `heapUsedBytes > 0`
- `heapUsedPercent` equals `(heapUsedBytes * 100.0) / heapMaxBytes` within a 0.01 delta
- `gcCollectionCount` is the arithmetic sum of all GC bean collection counts
- `sampledAt` is within 2 seconds of `Instant.now()`

---

#### Task 3.7: Create TelemetryResource
**Priority:** Critical
**Estimated Time:** 1.5 hours
**Labels:** `boundary`, `sse`, `jax-rs`

**Description:**
Implement the JAX-RS resource that streams live JVM metrics via Server-Sent Events and provides a one-shot snapshot endpoint for non-streaming clients.

**Acceptance Criteria:**
- [ ] `TelemetryResource` is `@Path("/telemetry")`, `@Secured`
- [ ] `GET /telemetry/stream` annotated `@Produces(SERVER_SENT_EVENTS)`, `@RolesAllowed("ADMIN")`
- [ ] Method signature: `void stream(@Context SseEventSink sink, @Context Sse sse)`
- [ ] Method body calls `telemetryControl.registerSink(sink)` and returns (the broadcaster handles the rest)
- [ ] `GET /telemetry/snapshot` annotated `@Produces(APPLICATION_JSON)`, `@RolesAllowed("ADMIN")`
- [ ] Snapshot method calls `telemetryControl.currentSnapshot()` and returns 200 with the snapshot

**Files to Create:**
```
src/main/java/com/pulse/boundary/
└── TelemetryResource.java
```

**Test Cases:**
- `GET /telemetry/stream` with ADMIN token responds with `Content-Type: text/event-stream`
- `GET /telemetry/stream` with ENGINEER token → 403
- `GET /telemetry/snapshot` with ADMIN token → 200 with `JvmTelemetrySnapshot` JSON containing `heapUsedBytes > 0`
- Open the SSE stream and verify at least 2 events arrive within 3 seconds

---

#### Task 3.8: Sprint 3 Verification — Async JAR Analysis and SSE Stream
**Priority:** Critical
**Estimated Time:** 1 hour
**Labels:** `verification`, `milestone`

**Description:**
Verify async JAR analysis and live SSE telemetry end-to-end.

**Acceptance Criteria:**
- [ ] Upload `helix-engine-core-1.0.0-SNAPSHOT.jar` → 202 with `Location` header
- [ ] Poll the `Location` URL every 2 seconds until `status == COMPLETED`
- [ ] Verify `classCount > 0` and `totalOpcodes > 0` in the completed analysis
- [ ] Open the SSE stream as ADMIN and receive at least 3 events over 5 seconds
- [ ] Each SSE event body is valid JSON parseable as `JvmTelemetrySnapshot`
- [ ] Verify an antipattern-triggering class fires a log line from `AntipatternObserver`
- [ ] All unit tests pass: `mvn test`

**Commands:**
```bash
# Upload helix-core JAR for analysis:
curl -X POST http://localhost:8080/api/v1/jars/analyze \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -F file=@/path/to/helix-engine-core-1.0.0-SNAPSHOT.jar

# Stream live telemetry:
curl -N http://localhost:8080/api/v1/telemetry/stream \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

---

### Sprint 3 Summary

**Total Tasks:** 8
**Total Estimated Time:** 12 hours
**Critical Path:** Task 3.4 → Task 3.5 → Task 3.6 → Task 3.7

**Deliverables:**
- Async JAR analysis using ManagedExecutorService with ASM opcode counting
- Three antipattern detectors with CDI @ObservesAsync event bus
- Live JVM telemetry SSE stream (heap, GC, threads) broadcasting every 1 second
- Complete RBAC enforcement across all endpoints

**End-of-Sprint Checklist:**
- [ ] All 8 tasks completed
- [ ] JAR analysis completes asynchronously and updates status to COMPLETED
- [ ] SSE stream delivers events every ~1 second
- [ ] Antipattern detection fires CDI events correctly
- [ ] Code committed with message `feat(sprint-3): jar analysis, async concurrency and SSE telemetry`

---


## Sprint 4: Performance Tuning, Docker & Portfolio Finalization (Day 7)

**Milestone:** M4 - Production Readiness & Portfolio Showcase
**Duration:** 8-10 hours
**Dependencies:** Sprint 3 completed

### Tasks

#### Task 4.1: Verify and Fix N+1 Select Problems
**Priority:** Critical
**Estimated Time:** 2 hours
**Labels:** `jpa`, `performance`, `n-plus-one`

**Description:**
Use Hibernate statistics (`hibernate.generate_statistics=true`) to identify any remaining N+1 select problems in `RuleSessionRepository.findAll()` and `JarAnalysisResource.findAll()`, then eliminate them using JPQL `JOIN FETCH` or `EntityGraph`.

**Acceptance Criteria:**
- [ ] Enable Hibernate statistics logging by adding `hibernate.statistics.statistics_enabled=true` and a `StatisticsService` bean that logs `getPrepareStatementCount()` before and after each repository method call
- [ ] `findAll()` on `RuleSession` with 20 rows and 2 metrics each must execute exactly 1 SQL statement using `SELECT DISTINCT s FROM RuleSession s JOIN FETCH s.metrics`
- [ ] `findHighOpcodeDensitySessions()` must execute exactly 1 SQL statement using the existing `EntityGraph` hint
- [ ] `findAll()` on `JarAnalysis` with 5 analyses and 10 class metrics each must execute at most 2 SQL statements using the existing `@BatchSize(size=20)` — verify via `getPrepareStatementCount() <= 2`
- [ ] No `LazyInitializationException` thrown when serializing entities to JSON in any endpoint

**Files to Modify:**
```
src/main/java/com/pulse/control/RuleSessionRepository.java
  - Modify findAll() to use JOIN FETCH

src/main/resources/META-INF/persistence.xml
  - Add hibernate.statistics.statistics_enabled=true
```

**Test Cases:**
- Call `repo.findAll()` with 20 sessions each having 2 metrics; assert `stats.getPrepareStatementCount() == 1`
- Call `repo.findHighOpcodeDensitySessions(0L)` with 5 sessions; assert statement count is 1
- Call jar analysis `findAll()` with 5 analyses, 10 class metrics each; assert statement count <= 2

---

#### Task 4.2: Tune HikariCP Connection Pool and Verify Configuration
**Priority:** High
**Estimated Time:** 1 hour
**Labels:** `performance`, `hikaricp`, `pool-tuning`

**Description:**
Verify the HikariCP pool properties set in Sprint 1 produce optimal throughput for the target environment. Run load tests and confirm no `SQLTimeoutException` occurs under 25 concurrent requests.

**Acceptance Criteria:**
- [ ] `maximumPoolSize` is set to `16` (formula: (4 CPU cores * 2) + 1 SSD spindle = 9, rounded to 16 for headroom)
- [ ] `minimumIdle` is set to `4`
- [ ] `connectionTimeout` is set to `3000` ms
- [ ] `idleTimeout` is set to `600000` ms (10 minutes)
- [ ] `maxLifetime` is set to `1800000` ms (30 minutes — less than PostgreSQL default `wait_timeout`)
- [ ] `poolName` is set to `CortexPool`
- [ ] Under 25 concurrent `POST /rules/compile` requests (using `ab -n 250 -c 25`), no `SQLTimeoutException` appears in WildFly logs
- [ ] Mean response time under load is less than 200ms

**Commands:**
```bash
# Load test compile endpoint:
ab -n 250 -c 25 -H "Authorization: Bearer $ENGINEER_TOKEN" \
  -T application/json \
  -p /tmp/rule.json \
  http://localhost:8080/api/v1/rules/compile
# Check: No "Connection is not available" in wildfly/standalone/log/server.log
```

---

#### Task 4.3: Create Docker Compose and Dockerfile
**Priority:** Critical
**Estimated Time:** 2 hours
**Labels:** `docker`, `deployment`, `infrastructure`

**Description:**
Create a multi-stage Dockerfile that builds the WAR inside a Maven container and runs it in a WildFly 31 container. Create a `docker-compose.yml` that starts PostgreSQL first, waits for it to be healthy, then starts the application.

**Acceptance Criteria:**
- [ ] `Dockerfile` uses two stages: `FROM maven:3.9-eclipse-temurin-17 AS builder` and `FROM quay.io/wildfly/wildfly:31.0.0.Final-jdk17`
- [ ] Build stage copies `pom.xml`, downloads dependencies (`mvn dependency:go-offline`), copies `src/`, and runs `mvn package -DskipTests`
- [ ] Runtime stage copies `helix-cortex.war` to `$JBOSS_HOME/standalone/deployments/`
- [ ] Runtime stage copies `docker/standalone.xml` to `$JBOSS_HOME/standalone/configuration/` — this standalone.xml defines the `CortexDS` PostgreSQL datasource using environment variables `DB_URL`, `DB_USER`, `DB_PASSWORD`
- [ ] `docker-compose.yml` defines two services: `postgres` and `jvm-pulse-ee`
- [ ] `postgres` service uses `postgres:16-alpine`, has `POSTGRES_DB=pulsedb`, has `healthcheck` using `pg_isready`
- [ ] `jvm-pulse-ee` service has `depends_on: postgres: condition: service_healthy`
- [ ] `jvm-pulse-ee` service exposes port 8080
- [ ] `.env.example` documents all required environment variables: `JWT_SECRET`, `DB_PASSWORD`
- [ ] `docker compose up --build` starts the stack and `POST /api/v1/auth/login` returns 200

**Files to Create:**
```
helix-cortex/
├── Dockerfile
├── docker-compose.yml
├── .env.example
└── docker/
    └── standalone.xml
```

**Test Cases:**
- `docker compose up --build` completes without errors
- `curl localhost:8080/api/v1/auth/login` with admin credentials returns 200 within 60 seconds of stack startup
- `docker compose down -v` stops and removes all containers and the postgres volume

---

#### Task 4.4: Create Postman Collection
**Priority:** High
**Estimated Time:** 1.5 hours
**Labels:** `testing`, `postman`, `documentation`

**Description:**
Create a Postman collection covering all 13 API endpoints with pre-request scripts that automatically handle JWT tokens and post-response tests that assert expected status codes and response shapes.

**Acceptance Criteria:**
- [ ] Collection variable `BASE_URL` set to `http://localhost:8080/api/v1`
- [ ] Collection variable `JWT_TOKEN` updated automatically by the login request's `Tests` tab: `pm.collectionVariables.set("JWT_TOKEN", pm.response.json().token)`
- [ ] Each request uses `{{BASE_URL}}` and `Authorization: Bearer {{JWT_TOKEN}}`
- [ ] `POST /auth/login` test: status is 200, body contains `token` key
- [ ] `POST /rules/compile` test: status is 201, body contains `id` key; `pm.collectionVariables.set("SESSION_ID", pm.response.json().id)`
- [ ] `POST /rules/execute/{{SESSION_ID}}` test: status is 200, body contains `executionTimeNanos > 0`
- [ ] `GET /rules/sessions` (ADMIN) test: status is 200, body is an array
- [ ] `GET /rules/sessions/mine` test: status is 200
- [ ] `GET /rules/sessions/high-density?threshold=0` test: status is 200
- [ ] `POST /jars/analyze` test: status is 202, headers contain `Location`
- [ ] `GET /telemetry/snapshot` test: status is 200, body contains `heapUsedBytes`
- [ ] Negative test: `GET /rules/sessions` with ENGINEER token → 403
- [ ] Negative test: any endpoint with no token → 401
- [ ] Collection exported to `postman/helix-cortex.postman_collection.json`

**Files to Create:**
```
helix-cortex/
└── postman/
    └── helix-cortex.postman_collection.json
```

---

#### Task 4.5: Write Portfolio README
**Priority:** High
**Estimated Time:** 1.5 hours
**Labels:** `documentation`, `portfolio`

**Description:**
Write the project README that serves as the portfolio showcase document. It must include the system architecture description, complete API reference table, setup instructions, Docker Compose quickstart, and a section on Jakarta EE concepts demonstrated.

**Acceptance Criteria:**
- [ ] `README.md` exists at the project root
- [ ] Section: Project overview paragraph (3-4 sentences on what helix-cortex does and why)
- [ ] Section: Architecture — text description of BCE layers and how helix-jvm-engine is integrated as a Maven dependency
- [ ] Section: Tech stack table listing Jakarta EE 10, WildFly 31, PostgreSQL 16, HikariCP, helix-jvm-engine 1.0.0-SNAPSHOT, ASM 9.5
- [ ] Section: API Reference table with 13 rows (method, path, role, description)
- [ ] Section: Quick Start — exactly 4 commands: clone, copy `.env.example`, fill in secrets, `docker compose up --build`
- [ ] Section: Jakarta EE Concepts Demonstrated — lists CDI 4.0, JAX-RS 3.1, JPA 3.1, JTA, Jakarta Security 3.0, SSE, ManagedExecutorService, @ObservesAsync, @NameBinding filter, PBKDF2PasswordHash
- [ ] Section: helix-jvm-engine Integration — explains what helix provides and what cortex adds on top
- [ ] Badge row at the top: Jakarta EE 10, WildFly 31, Java 17, License

---

#### Task 4.6: Add OpenAPI Annotations and Verify Swagger UI
**Priority:** Medium
**Estimated Time:** 1 hour
**Labels:** `documentation`, `openapi`

**Description:**
Add MicroProfile OpenAPI annotations to all JAX-RS resources and verify that Swagger UI is accessible at the WildFly MicroProfile OpenAPI endpoint.

**Acceptance Criteria:**
- [ ] `microprofile-openapi-api` dependency added with `<scope>provided</scope>`
- [ ] `@OpenAPIDefinition` on `CortexApplication` with title `helix-cortex API`, version `1.0.0`, and description
- [ ] `@Tag(name="auth")`, `@Tag(name="rules")`, `@Tag(name="jars")`, `@Tag(name="telemetry")` on respective resources
- [ ] `@Operation(summary="...")` on each endpoint method
- [ ] `@APIResponse(responseCode="200", description="...")` on each endpoint method
- [ ] `@SecurityScheme` declared for Bearer token
- [ ] `GET /openapi` returns a valid OpenAPI 3.0 YAML document listing all 13 endpoints

---

#### Task 4.7: Sprint 4 Verification — Full Stack Docker Validation
**Priority:** Critical
**Estimated Time:** 1 hour
**Labels:** `verification`, `milestone`, `docker`

**Description:**
Run the complete stack via Docker Compose and execute the full Postman collection to verify every endpoint against the containerized deployment.

**Acceptance Criteria:**
- [ ] `docker compose up --build` completes without errors
- [ ] All 13 Postman requests pass their assertions (0 failures)
- [ ] N+1 verification: `GET /rules/sessions` with 20 sessions logs `PreparedStatements: 1` in WildFly server.log
- [ ] Pool verification: `ab -n 250 -c 25` on `/rules/compile` produces 0 `SQLTimeoutException` entries in logs
- [ ] SSE stream verified: `curl -N` receives at least 5 events over 6 seconds
- [ ] `docker compose down` shuts down cleanly
- [ ] Final Git tag `v1.0.0` created and pushed

**Commands:**
```bash
docker compose up --build -d
sleep 60   # wait for startup

# Run Postman collection via Newman:
npx newman run postman/helix-cortex.postman_collection.json \
  --env-var "BASE_URL=http://localhost:8080/api/v1"
# Expected: 0 failures

git tag v1.0.0
git push origin v1.0.0

docker compose down
```

---

### Sprint 4 Summary

**Total Tasks:** 7
**Total Estimated Time:** 10 hours
**Critical Path:** Task 4.1 → Task 4.3 → Task 4.7

**Deliverables:**
- Zero N+1 select queries across all list endpoints
- HikariCP pool verified under 25-concurrent-request load
- Docker Compose one-command startup
- Full Postman collection with 13 passing tests
- Portfolio-grade README and OpenAPI documentation
- Git tag v1.0.0

**End-of-Sprint Checklist:**
- [ ] All 7 tasks completed
- [ ] Postman collection passes with 0 failures against Docker stack
- [ ] N+1 statement count verified as 1 for all list endpoints
- [ ] No SQLTimeoutException under load
- [ ] README and OpenAPI documentation published
- [ ] v1.0.0 tagged and pushed to GitHub

---


## File Map Summary

```
helix-cortex/
├── pom.xml
├── Dockerfile
├── docker-compose.yml
├── .env.example
├── README.md
├── postman/
│   └── helix-cortex.postman_collection.json
├── docker/
│   └── standalone.xml
├── docs/superpowers/specs/
│   └── IMPLEMENTATION_PLAN.md
└── src/main/
    ├── java/com/pulse/
    │   ├── app/
    │   │   └── CortexApplication.java
    │   ├── boundary/
    │   │   ├── AuthResource.java
    │   │   ├── RuleResource.java
    │   │   ├── JarAnalysisResource.java
    │   │   ├── TelemetryResource.java
    │   │   ├── GlobalExceptionMapper.java
    │   │   ├── dto/
    │   │   │   ├── LoginRequest.java
    │   │   │   ├── LoginResponse.java
    │   │   │   ├── RegisterRequest.java
    │   │   │   ├── RuleRequest.java
    │   │   │   ├── ExecutionRequest.java
    │   │   │   ├── JvmTelemetrySnapshot.java
    │   │   │   └── ProblemDetail.java
    │   │   └── filter/
    │   │       ├── Secured.java
    │   │       ├── JwtSecurityFilter.java
    │   │       ├── JwtSecurityContext.java
    │   │       ├── TokenClaims.java
    │   │       ├── InvalidTokenException.java
    │   │       └── TokenExpiredException.java
    │   ├── control/
    │   │   ├── HelixProducer.java
    │   │   ├── TokenService.java
    │   │   ├── EngineerAccountRepository.java
    │   │   ├── AuditLogRepository.java
    │   │   ├── RuleSessionControl.java
    │   │   ├── RuleSessionRepository.java
    │   │   ├── JarAnalysisControl.java
    │   │   ├── OpcodeCountingVisitor.java
    │   │   ├── TelemetryControl.java
    │   │   ├── AntipatternObserver.java
    │   │   ├── RuleAntipatternEvent.java
    │   │   └── AntipatternType.java
    │   └── entity/
    │       ├── EngineerAccount.java
    │       ├── EngineRole.java
    │       ├── RuleSession.java
    │       ├── OpcodeMetric.java
    │       ├── SessionStatus.java
    │       ├── JarAnalysis.java
    │       ├── AnalysisClassMetric.java
    │       ├── AnalysisStatus.java
    │       └── AuditLog.java
    ├── resources/
    │   └── META-INF/
    │       ├── persistence.xml
    │       └── initial-data.sql
    └── webapp/
        └── WEB-INF/
            └── web.xml
```

---

## Global Acceptance Criteria

- [ ] `mvn clean package` produces `target/helix-cortex.war` without warnings
- [ ] All 13 API endpoints documented in README API Reference table
- [ ] Postman collection covers all 13 endpoints with status code assertions
- [ ] No SQL string concatenation in any repository method
- [ ] No `LazyInitializationException` in any serialized endpoint response
- [ ] All list endpoints execute at most 2 SQL statements (verified via Hibernate statistics)
- [ ] `docker compose up --build` starts full stack within 90 seconds
- [ ] Git history has one commit per sprint with a `feat(sprint-N)` message prefix
- [ ] `v1.0.0` tag pushed to GitHub before LinkedIn post
