package com.gaplin.music

internal object DownloadFailurePolicy {
    fun userMessage(raw: String, hasOwnSession: Boolean, isYouTube: Boolean): String {
        val value = raw.lowercase()

        if ("unsupported downloader option" in value) {
            return "O motor de download recebeu uma opção incompatível. A configuração foi registrada para correção; atualize o Gaplin Music para a versão mais recente."
        }

        if ("this video is not available" in value ||
            "video unavailable" in value ||
            "not available in your country" in value
        ) {
            return "A fonte informou que este vídeo não está disponível para esta sessão. Ele pode ter sido removido, tornado privado ou restringido por região. Escolha outro resultado ou confirme o acesso no site oficial."
        }

        if ("members-only" in value || "sign in to confirm" in value ||
            "age-restricted" in value || "confirm your age" in value ||
            "private video" in value
        ) {
            return if (hasOwnSession) {
                "Este conteúdo exige autenticação ou autorização adicional. Confira se sua sessão própria ainda está válida e se sua conta tem acesso ao vídeo."
            } else {
                "Este conteúdo exige uma sessão autorizada. Importe um arquivo de cookies Netscape exportado da sua própria conta, se você já tiver acesso ao vídeo."
            }
        }

        if ("po token" in value || "potoken" in value || "proof of origin" in value ||
            "botguard" in value || "visitor_data" in value
        ) {
            return "O YouTube exige um token oficial de validação para esta sessão. O Gaplin não fabrica nem contorna esse controle; tente outro formato ou conteúdo disponível publicamente."
        }

        if ("requested format is not available" in value ||
            "no video formats found" in value ||
            "requested format not available" in value
        ) {
            return "A fonte não ofereceu um formato compatível. Para vídeo, o Gaplin exige áudio e imagem no mesmo fluxo; para áudio, precisa existir uma faixa de áudio. Tente outro resultado."
        }

        if ("403" in value || "http error 403" in value || "forbidden" in value) {
            return if (isYouTube) {
                if (hasOwnSession) {
                    "O YouTube recusou a transferência (HTTP 403). Sua sessão pode ter expirado ou o conteúdo pode exigir validação adicional."
                } else {
                    "O YouTube recusou a transferência (HTTP 403). Para conteúdo que exige login, importe sua própria sessão autorizada. Alguns vídeos exigem validação adicional do YouTube."
                }
            } else {
                "A fonte recusou a transferência (HTTP 403). Confira a disponibilidade e a autorização para acessar este conteúdo."
            }
        }

        return raw.trim().take(500).ifBlank { "Falha desconhecida no downloader." }
    }
}
