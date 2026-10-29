# Downloads do Gaplin Music

A aba Downloads usa a biblioteca Android do yt-dlp mantida em
https://github.com/JunkFood02/youtubedl-android.

## Arquitetura

- Jetpack Compose: interface de consulta de link, escolha de mídia e fila.
- yt-dlp: consulta de metadados e extração de formatos.
- FFmpeg: remux de vídeo e conversão de áudio para MP3.
- WorkManager: execução persistente, progresso, cancelamento e notificações.
- MediaStore Downloads: publicação dos arquivos em Download/Gaplin Music no Android 10 ou superior.
- Armazenamento específico do aplicativo: área temporária e fallback em versões anteriores do Android.

## Requisitos

A biblioteca yt-dlp para Android requer Android API 24 ou superior. O APK inclui bibliotecas nativas para arm64-v8a e armeabi-v7a. A atualização do mecanismo yt-dlp deve ser feita de forma controlada e validada antes de uma versão de produção.

## Uso responsável

O aplicativo não tenta remover DRM, contornar paywalls, autenticação, controles de acesso ou restrições privadas. O usuário deve ter direito ou autorização para salvar o conteúdo, e cada plataforma pode impor condições próprias de download.
