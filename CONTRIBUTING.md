# Contributing to OrgSec

Thank you for your interest in contributing to OrgSec!

## Getting Started

1. Fork the repository
2. Clone your fork:
   ```bash
   git clone https://github.com/your-username/orgsec.git
   cd orgsec
   ```
3. Build the project:
   ```bash
   mvn clean install
   ```

## Development Setup

### Requirements

- Java 17+
- Maven 3.6+
- Docker (for Redis integration tests)

### Project Structure

The project uses a Maven multi-module structure. See [README.md](README.md) for module descriptions.

### Building

```bash
# Full build with tests
mvn clean install

# Build without tests (faster)
mvn clean install -DskipTests

# Build a specific module
mvn clean install -pl orgsec-core

# Build a module and its dependencies
mvn clean install -pl orgsec-storage-redis -am
```

### Running Tests

```bash
# All tests
mvn test

# Tests for a specific module
mvn test -pl orgsec-core

# Integration tests (requires Docker for Redis)
mvn verify -pl orgsec-storage-redis
```

## Making Changes

### Branch Naming

- `feature/description` - new features
- `fix/description` - bug fixes
- `refactor/description` - refactoring

### Code Style

- Follow existing code conventions in the project
- Use meaningful variable and method names
- Keep methods focused and concise

### Commit Messages

Use clear, descriptive commit messages:

```
feat: add batch operations to SecurityDataStorage
fix: resolve thread safety issue in InMemorySecurityDataStorage
refactor: extract cache key building into CacheKeyBuilder
docs: update README with Redis configuration examples
```

Prefixes: `feat`, `fix`, `refactor`, `docs`, `test`, `chore`

### Testing

- Write unit tests for new functionality
- Ensure existing tests pass before submitting
- The Redis module enforces 85% line coverage and 80% branch coverage via JaCoCo

## Submitting Changes

1. Create a feature branch from `develop`
2. Make your changes with clear commits
3. Ensure all tests pass: `mvn clean verify`
4. Push to your fork and create a Pull Request against `develop`
5. Describe your changes in the PR description

## Reporting Issues

- Use [GitHub Issues](https://github.com/Nomendi6/orgsec/issues)
- Include steps to reproduce, expected vs actual behavior, and environment details

## License

By contributing, you agree that your contributions will be licensed under the [Apache License 2.0](LICENSE).
