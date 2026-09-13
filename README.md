# helix-cortex — Jakarta EE 10 Enterprise API & Observability Layer

`helix-cortex` (from "cerebral cortex" — the intelligent outer layer) is a production-grade Jakarta EE 10 REST microservice that wraps `helix-jvm-engine` as its execution core.

---

## High-Level Capabilities

- **Dynamic Rule Compilation & Evaluation REST Pipeline:** Exposes high-throughput bytecode rule evaluation backed by the `helix-jvm-engine` core.
- **Deep JAR Bytecode Analysis:** Asynchronously unpacks and inspects uploaded JAR files, counting opcodes and identifying antipatterns via ASM.
- **Real-Time JVM Telemetry Streaming:** Streams live HotSpot JVM MXBeans metrics (heap, GC, thread states) via Server-Sent Events (SSE).
- **Stateless Security & RBAC:** Protected with manual HMAC-SHA256 JWT tokens and PBKDF2 password hashing supporting `ENGINEER` and `ADMIN` roles.
- **Enterprise Persistence:** JPA 3.1, Criteria API analytics, and HikariCP connection pool tuning over PostgreSQL 16.
