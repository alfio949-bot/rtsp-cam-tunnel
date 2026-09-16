package com.rtsptunnel.cam.rtsp

import com.rtsptunnel.cam.net.TunnelSocketFactory
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import javax.net.SocketFactory

/**
 * Esito del tentativo di accesso iniziale (anonimo) alla telecamera.
 */
sealed class ProbeResult {
    /** 200 OK: la camera è aperta, nessuna autenticazione richiesta. */
    object Open : ProbeResult()

    /** 401 Unauthorized: la camera richiede credenziali. */
    object Unauthorized : ProbeResult()

    /** Qualsiasi altro problema (irraggiungibile, timeout, 403, ecc.). */
    data class Failure(val message: String) : ProbeResult()
}

/**
 * Client RTSP minimale per il "rilevamento e tentativo automatico":
 * invia OPTIONS + DESCRIBE SENZA credenziali (accesso anonimo) e legge
 * solo il codice di stato della risposta, come richiesto:
 *
 *  - 200 -> la camera è aperta  -> il chiamante avvia il playback
 *  - 401 -> autenticazione richiesta -> il chiamante interrompe il
 *    tentativo automatico e apre il modulo di login
 *
 * Anche questo socket passa dalla SocketFactory fornita (quindi dal
 * tunnel proxy se configurato): l'handshake iniziale NON rivela l'IP reale.
 */
class RtspProbe(private val socketFactory: SocketFactory?) {

    fun probeAnonymous(rtspUri: URI): ProbeResult {
        val host = rtspUri.host ?: return ProbeResult.Failure("URL RTSP senza host")
        val port = if (rtspUri.port > 0) rtspUri.port else DEFAULT_RTSP_PORT
        val requestTarget = "rtsp://$host:$port${rtspUri.path.ifEmpty { "/" }}"

        var socket: Socket? = null
        try {
            socket = openSocket(host, port)
            val out = socket.getOutputStream()

            // --- OPTIONS (CSeq 1) ---
            out.write(
                ("OPTIONS $requestTarget RTSP/1.0\r\n" +
                        "CSeq: 1\r\n" +
                        "User-Agent: RTSPCamTunnel/1.0\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
            )
            out.flush()
            val optionsStatus = readResponse(socket)      // es. "RTSP/1.0 200 OK"
            // alcune camera chiuse rispondono già 401 a OPTIONS: è comunque un segnale di auth
            codeOf(optionsStatus)?.let { code ->
                if (code == 401) return ProbeResult.Unauthorized
                if (code !in 200..299) return ProbeResult.Failure("Risposta OPTIONS inattesa: $code")
            }

            // --- DESCRIBE anonimo (CSeq 2) ---
            out.write(
                ("DESCRIBE $requestTarget RTSP/1.0\r\n" +
                        "CSeq: 2\r\n" +
                        "Accept: application/sdp\r\n" +
                        "User-Agent: RTSPCamTunnel/1.0\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
            )
            out.flush()
            val describeStatus = readResponse(socket)

            return when (val code = codeOf(describeStatus)) {
                in 200..299 -> ProbeResult.Open
                401 -> ProbeResult.Unauthorized
                403 -> ProbeResult.Failure("Accesso negato dalla telecamera (403)")
                404 -> ProbeResult.Failure("Percorso stream non trovato (404): verifica l'URL")
                null -> ProbeResult.Failure("Risposta RTSP non interpretabile: \"$describeStatus\"")
                else -> ProbeResult.Failure("Errore RTSP $code: ${describeStatus.substringAfter(' ', "")}")
            }
        } catch (e: SocketTimeoutException) {
            return ProbeResult.Failure("Timeout: la telecamera non risponde (controlla IP/porta)")
        } catch (e: IOException) {
            return ProbeResult.Failure("Impossibile raggiungere la telecamera: ${e.message}")
        } catch (e: Exception) {
            return ProbeResult.Failure("Errore connessione: ${e.message}")
        } finally {
            runCatching { socket?.close() }
        }
    }

    /**
     * Socket dal tunnel (se attivo) o diretto (se consentito dall'utente).
     * La variante createSocket(host, port) di javax.net ritorna un socket
     * GIÀ CONNESSO — media3 usa la stessa identica chiamata.
     */
    private fun openSocket(host: String, port: Int): Socket {
        val factory = socketFactory ?: SocketFactory.getDefault()
        val socket = if (factory is TunnelSocketFactory)
            factory.createSocket(host, port)   // tunnel: DNS risolto dal proxy
        else
            factory.createSocket(host, port)   // connessione diretta (se consentita)
        socket.soTimeout = 6_000
        return socket
    }

    /** Legge status line + intestazioni di una risposta RTSP. */
    private fun readResponse(socket: Socket): String {
        val input = socket.getInputStream()
        var statusLine = readLine(input)
        // consuma le intestazioni fino alla riga vuota
        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) break
        }
        return statusLine
    }

    private fun readLine(input: java.io.InputStream): String {
        val sb = StringBuilder(128)
        while (true) {
            val b = input.read()
            if (b == -1) throw IOException("Connessione chiusa dalla telecamera")
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun codeOf(statusLine: String): Int? =
        statusLine.split(" ").getOrNull(1)?.toIntOrNull()

    companion object {
        const val DEFAULT_RTSP_PORT = 554
    }
}
