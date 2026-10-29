# Gaplin Music — Download Engine

## Objetivo

Implementar uma experiência de seleção de formatos inspirada no fluxo de UX mostrado pelo usuário, com código próprio e adaptadores independentes. A interface não presume que uma plataforma permita baixar qualquer conteúdo.

## Camadas

1. **Discovery/UI** — pesquisa, histórico, atalhos de fontes e resultados.
2. **MediaSourceRegistry** — registra adaptadores independentes.
3. **MediaSourceAdapter** — pesquisa e resolve formatos somente por APIs oficiais, permissões explícitas ou URLs de mídia fornecidas pelo próprio usuário.
4. **Format selector** — exibe formatos reais retornados pelo adaptador; não inventa resoluções, bitrates ou tamanhos.
5. **Transfer** — Android DownloadManager para URLs diretas HTTP(S), com notificações e transferência em segundo plano.
6. **Processing** — conversão/mux somente para arquivos locais ou conteúdo cuja licença/permissão permita processamento.
7. **Library** — indexação, duplicatas, metadados e reprodução offline.

## Restrições obrigatórias

- Não contornar DRM, paywalls, autenticação, tokens de acesso, controles de privacidade ou restrições técnicas das plataformas.
- Não coletar cookies/sessões do navegador para contornar controles de acesso.
- Não expor chaves ou credenciais em código cliente.
- Um adaptador deve retornar erro explícito quando a fonte não oferecer um método autorizado de download.
- Só apresentar formatos, legendas, resoluções e tamanhos que a fonte realmente informou.
- Downloads de arquivos próprios, domínio controlado pelo usuário e fontes com autorização explícita podem usar a camada de transferência.

## Componentes já adicionados

- `DownloadFormatSheet.kt`: seletor Compose de formatos.
- `MediaSource.kt`: contratos de pesquisa/resolução e registro de adaptadores.
- `DirectDownloadManager.kt`: ponte para Android DownloadManager com validação HTTP(S).

## Próximas tarefas

- Integrar a aba/rota de downloads na navegação existente sem alterar o player.
- Criar estado de UI para pesquisa, resultado, seleção e fila.
- Implementar adaptadores para fontes que ofereçam API oficial/permissionada.
- Criar testes unitários para validação de URL, nomes de arquivo, registry e seleção de formatos.
- Adicionar teste de build no GitHub Actions para a branch de feature.
