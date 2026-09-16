package com.rtsptunnel.cam.net

import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import javax.net.SocketFactory

/**
 * Livello di "mascheramento IP": ogni socket creato da questa factory
 * (sia per l'handshake RTSP di probe sia per lo streaming del player)
 * viene instradato OBBLIGATORIAMENTE attraverso il server proxy scelto
 * dall'utente. La telecamera vede quindi solo l'indirizzo del proxy,
 * mai l'IP reale dello smartphone.
 */
sealed class Tunnel {
    /** Proxy SOCKS5: la risoluzione del nome avviene sul proxy
     *  (usiamo InetSocketAddress non risolti per evitare DNS leak locali). */
    data class Socks5(val host: String, val port: Int) : Tunnel()

    /** Proxy HTTP con metodo CONNECT (tunnel TCP manuale). */
    data class HttpConnect(val host: String, val port: Int) : Tunnel()
}

object TunnelParser {

    /**
     * Accetta: "socks5://host:port", "http://host:port", "host:port"
     * (default socks5). Ritorna null su stringa vuota; lancia
     * IllegalArgumentException su formato invalido.
     */
    fun parse(raw: String): Tunnel? {
        val s = raw.trim()
        if (s.isEmpty()) return null

        val (scheme, rest) = if (s.contains("://")) {
            val idx = s.indexOf("://")
            s.substring(0, idx).lowercase() to s.substring(idx + 3)
        } else {
            "socks5" to s
        }

        // ignora eventuali credenziali nel proxy URL (non supportate qui)
        val hostPort = rest.substringAfter('@').substringBefore('/')
        val host = hostPort.substringBeforeLast(':')
        val port = hostPort.substringAfterLast(':').toIntOrNull()
            ?: throw IllegalArgumentException("Porta proxy mancante o non numerica")

        if (host.isEmpty() || port !in 1..65535) {
            throw IllegalArgumentException("Host o porta proxy non validi")
        }

        return when (scheme) {
            "socks5", "socks", "socks5h" -> Tunnel.Socks5(host, port)
            "http", "https" -> Tunnel.HttpConnect(host, port)
            else -> throw IllegalArgumentException("Schema proxy non supportato: $scheme (usa socks5:// o http://)")
        }
    }
}

/**
 * SocketFactory personalizzata iniettabile sia nel probe RTSP sia in
 * RtspMediaSource.Factory (ExoPlayer/media3 supporta socket factory custom).
 * Con setForceUseRtpTcp(true) anche il flusso RTP viaggia interleaved sullo
 * stesso socket TCP, quindi l'INTERO traffico passa dal tunnel.
 */
class TunnelSocketFactory(private val tunnel: Tunnel) : SocketFactory() {

    /** Helper interno: NON fa override (su Android la classe base non ha
     *  il createSocket() senza argomenti) ma è usato da RtspProbe. */
    fun createTunnelSocket(): Socket = when (tunnel) {
        is Tunnel.Socks5 ->
            // java.net.Socket con proxy SOCKS: handshake SOCKS5 gestito dalla JVM.
            Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress(tunnel.host, tunnel.port)))
        is Tunnel.HttpConnect ->
            // Tunnel CONNECT manuale: la connect() reale viene intercettata.
            HttpConnectTunnelSocket(tunnel)
    }

    override fun createSocket(host: String, port: Int): Socket =
        createSocket().also {
            // Endpoints NON risolti: il nome passa al proxy (niente DNS locale,
            // niente leak dell'IP verso il resolver di casa).
            it.connect(InetSocketAddress.createUnresolved(host, port), CONNECT_TIMEOUT_MS)
        }

    // Variante javax.net: ritorna un socket tunnel già connesso.
    override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress, localPort: Int): Socket =
        createSocket(host, port)

    override fun createSocket(host: java.net.InetAddress, port: Int): Socket =
        createSocket(host.hostAddress, port)

    override fun createSocket(host: java.net.InetAddress, port: Int, localHost: java.net.InetAddress, localPort: Int): Socket =
        createSocket(host.hostAddress, port)

    companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
    }
}

/**
 * Socket che al momento della connect() stabilisce prima la connessione al
 * proxy HTTP, invia "CONNECT host:port HTTP/1.1" e solo dopo una risposta
 * "200" lascia parlare il protocollo RTSP direttamente col server camera.
 */
class HttpConnectTunnelSocket(private val proxy: Tunnel.HttpConnect) : Socket() {

    override fun connect(endpoint: java.net.SocketAddress?, timeout: Int) {
        val addr = endpoint as? InetSocketAddress
            ?: throw IOException("Endpoint di connessione non valido")

        // 1) TCP verso il proxy
        super.connect(InetSocketAddress(proxy.host, proxy.port), timeout)

        // 2) Handshake CONNECT verso la destinazione reale
        val target = addr.hostString  // NON risolto localmente se il caller usa createUnresolved
        val request = buildString {
            append("CONNECT $target:${addr.port} HTTP/1.1\r\n")
            append("Host: $target:${addr.port}\r\n")
            append("Proxy-Connection: keep-alive\r\n\r\n")
        }
        getOutputStream().apply {
            write(request.toByteArray(Charsets.ISO_8859_1))
            flush()
        }

        // 3) Risposta del proxy: prima riga "HTTP/1.1 200 ..." = tunnel stabilito
        soTimeout = 8_000
        val input = getInputStream()
        val statusLine = readLine(input)
        if (!statusLine.contains("200")) {
            close()
            throw IOException("Proxy CONNECT rifiutato dal server proxy: $statusLine")
        }
        // consuma le intestazioni restanti fino alla riga vuota
        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) break
        }
    }

    private fun readLine(input: InputStream): String {
        val sb = StringBuilder(64)
        while (true) {
            val b = input.read()
            if (b == -1) throw IOException("Connessione al proxy chiusa inaspettatamente")
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }
}
