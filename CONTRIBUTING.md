# Contributing to The Vault

Thanks for your interest in contributing. The notes below help you get a reliable dev setup and submit clean PRs.

## Development Setup
- Android Studio (latest stable)
- JDK 11
- Android SDK 34
- Device or emulator with API 26+

### Build
```bash
./gradlew assembleDebug
```

### Run Tests
```bash
./gradlew test
```

### Lint
```bash
./gradlew lint
```

## Code Style
- Match existing Kotlin and Compose conventions in the repo.
- Prefer clear, descriptive names for functions and variables.
- Keep UI state in ViewModels when possible and avoid heavy logic in Composables.
- Add comments only when the behavior is non-obvious.

## Commit Conventions
- Use present tense, concise messages. Example: `Add backup integrity check`.
- Reference issues when relevant. Example: `Fixes #123`.

## Pull Request Process
1. Fork the repo and create a feature branch from `main`.
2. Make your changes and run tests or lint relevant to your changes.
3. Open a PR with a clear description and screenshots for UI changes.
4. Expect feedback and iterate as needed.

## Security Issues
If you discover a security issue, please follow the guidance in `SECURITY.md`.
