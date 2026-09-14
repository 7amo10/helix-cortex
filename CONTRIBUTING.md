# Contributing to helix-cortex

Thank you for your interest in contributing to **helix-cortex**! We welcome pull requests, bug reports, feature suggestions, and documentation improvements.

---

## Code of Conduct

Please maintain a professional, courteous, and constructive environment in all interactions across issues, pull requests, and discussions.

---

## Development Workflow

### 1. Prerequisites
- **Java Development Kit (JDK):** Version 17 or higher.
- **Build System:** Apache Maven 3.8+.
- **Database:** PostgreSQL 16+ or Docker Compose.
- **Application Server:** WildFly 31.0.0.Final.
- **Version Control:** Git.

### 2. Fork and Branching Model
- Fork the official repository `7amo10/helix-cortex`.
- Create a feature or bugfix branch off `develop`:
  ```bash
  git checkout develop
  git pull origin develop
  git checkout -b task/X.Y-feature-name
  ```

### 3. Build & Verification Checklist
Before submitting a pull request, ensure all Maven tests pass cleanly:

```bash
mvn clean test
```

Verify that all boundary resources, control beans, JPA repositories, and security filters execute with zero failures and that N+1 select queries remain eliminated.

---

## Commit Guidelines

- Write clear, concise commit messages following Conventional Commits:
  - `feat(rules): add bytecode validation check`
  - `fix(security): correct JWT expiration validation`
  - `perf(jpa): optimize rule session query fetching`
  - `test(boundary): add integration test for telemetry endpoint`
- **Do not include emojis** in commit titles, branch names, pull request descriptions, or code documentation.

---

## Pull Request Submission

1. Push your feature branch to your fork.
2. Open a Pull Request targeting the `develop` branch.
3. Complete the pull request template with a summary of changes, test evidence, and linked issues.
