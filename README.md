# Gaplin Music

Reprodutor multimídia Android nativo da Gaplin Tecnologia.

## Stack
- Kotlin
- Jetpack Compose / Material 3
- AndroidX Media3 (ExoPlayer)
- MediaSessionService

## Estado
Base inicial do aplicativo. A compilação e os testes devem ser executados no Ubuntu/Android SDK.

## Build
```bash
chmod +x gradlew
./gradlew testDebugUnitTest assembleDebug
```

Consulte `docs/UBUNTU_BUILD.md` para preparar o ambiente.
