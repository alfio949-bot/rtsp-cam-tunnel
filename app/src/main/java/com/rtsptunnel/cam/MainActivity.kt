package com.rtsptunnel.cam

import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.ui.PlayerView
import com.rtsptunnel.cam.net.TunnelParser
import com.rtsptunnel.cam.net.TunnelSocketFactory
import com.rtsptunnel.cam.player.CameraPlayer
import com.rtsptunnel.cam.rtsp.ProbeResult
import com.rtsptunnel.cam.rtsp.RtspProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import javax.net.SocketFactory

class MainActivity : AppCompatActivity(), CameraPlayer.Callbacks {

    private lateinit var playerView: PlayerView
    private lateinit var etUrl: EditText
    private lateinit var etProxy: EditText
    private lateinit var cbRequireProxy: CheckBox
    private lateinit var btnConnect: Button
    private lateinit var tvStatus: TextView

    private lateinit var cameraPlayer: CameraPlayer

    /** Tunnel attivo per la sessione corrente: null = connessione diretta
     *  (consentita solo se l'utente disattiva la modalità privacy). */
    private var tunnelFactory: SocketFactory? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        playerView = findViewById(R.id.playerView)
        etUrl = findViewById(R.id.etUrl)
        etProxy = findViewById(R.id.etProxy)
        cbRequireProxy = findViewById(R.id.cbRequireProxy)
        btnConnect = findViewById(R.id.btnConnect)
        tvStatus = findViewById(R.id.tvStatus)

        cameraPlayer = CameraPlayer(this, playerView)

        btnConnect.setOnClickListener { attemptConnection() }
    }

    // ------------------------------------------------------------------
    // 1) Rilevamento e tentativo automatico (accesso anonimo)
    // ------------------------------------------------------------------
    private fun attemptConnection() {
        val raw = etUrl.text.toString().trim()
        if (raw.isEmpty()) {
            setStatus("Inserisci l'indirizzo della telecamera (es. 192.168.1.100)")
            return
        }
        val uri = normalizeRtsp(raw) ?: run {
            setStatus("Indirizzo non valido: es. rtsp://192.168.1.100:554/ oppure solo l'IP")
            return
        }

        // --- configurazione del tunnel di anonimato ---
        val proxyRaw = etProxy.text.toString().trim()
        val tunnel = try {
            TunnelParser.parse(proxyRaw)
        } catch (e: IllegalArgumentException) {
            setStatus(e.message ?: "Proxy non valido")
            return
        }
        if (tunnel == null && cbRequireProxy.isChecked) {
            setStatus(
                "Modalità privacy attiva: serve un proxy SOCKS5/HTTP per nascondere " +
                        "il tuo IP. Inseriscine uno oppure disattiva il vincolo " +
                        "(la connessione esporrebbe il tuo indirizzo reale)."
            )
            return
        }
        tunnelFactory = tunnel?.let { TunnelSocketFactory(it) }

        setStatus("Tentativo anonimo verso ${uri.host}…" +
                if (tunnel != null) " (via tunnel, il tuo IP è nascosto)" else " (diretta: IP visibile!)")

        lifecycleScope.launch(Dispatchers.IO) {
            val result = RtspProbe(tunnelFactory).probeAnonymous(uri)
            withContext(Dispatchers.Main) {
                when (result) {
                    // 2a) Camera aperta: avvio immediato del video
                    is ProbeResult.Open -> {
                        setStatus("Camera aperta (nessuna autenticazione richiesta) – avvio streaming…")
                        play(buildRtspUrl(uri, null, null))
                    }
                    // 2b) 401: interrompo il tentativo automatico e chiedo le credenziali
                    is ProbeResult.Unauthorized -> {
                        setStatus("La telecamera richiede l'autenticazione (401 Unauthorized).")
                        showLoginDialog(uri)
                    }
                    is ProbeResult.Failure -> setStatus("Impossibile connettersi: ${result.message}")
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 3) Fallback con password: dialog Username/Password
    // ------------------------------------------------------------------
    private fun showLoginDialog(uri: URI) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val etUser = EditText(this).apply { hint = "Username" }
        val etPass = EditText(this).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        container.addView(etUser)
        container.addView(etPass)

        AlertDialog.Builder(this)
            .setTitle("Autenticazione telecamera")
            .setMessage("La camera ${uri.host} ha rifiutato l'accesso anonimo (401). Inserisci le credenziali.")
            .setView(container)
            .setPositiveButton("Connetti") { _, _ ->
                // 4) Connessione autenticata: URL rigenerato con le credenziali
                play(buildRtspUrl(uri, etUser.text.toString(), etPass.text.toString()))
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    // ------------------------------------------------------------------
    // 4) Riproduzione (anonima o autenticata)
    // ------------------------------------------------------------------
    private fun play(rtspUrl: String) {
        setStatus("Connessione al flusso…")
        cameraPlayer.start(rtspUrl, tunnelFactory, this)
    }

    // ------------------------- callback del player --------------------
    override fun onPlaying() {
        setStatus(
            if (tunnelFactory != null) "Streaming attivo (traffico instradato via proxy: IP mascherato)"
            else "Streaming attivo (connessione diretta: il tuo IP è visibile alla camera)"
        )
    }

    override fun onAuthError() {
        setStatus("Credenziali errate o mancanti (401). Riprova: riapri il login con «Connetti».")
        Toast.makeText(this, "Credenziali rifiutate dalla telecamera", Toast.LENGTH_LONG).show()
    }

    override fun onGenericError(message: String?) {
        setStatus("Errore di riproduzione: ${message ?: "sconosciuto"}")
    }

    // ------------------------- utilità -------------------------------
    /** Normalizza l'input: aggiunge schema rtsp:// e default porta 554. */
    private fun normalizeRtsp(raw: String): URI? {
        val withScheme =
            if (raw.startsWith("rtsp://", ignoreCase = true)) raw
            else "rtsp://$raw"
        return try {
            val u = URI(withScheme)
            if (u.host.isNullOrBlank()) null else u
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Rigenera l'URL RTSP nel formato standard
     * rtsp://username:password@ip:porta/percorso (credenziali URL-encoded).
     * Con credenziali nulle produce l'URL anonimo.
     */
    private fun buildRtspUrl(uri: URI, user: String?, pass: String?): String {
        val port = if (uri.port > 0) uri.port else RtspProbe.DEFAULT_RTSP_PORT
        val path = uri.path.ifEmpty { "/" }
        val creds = if (!user.isNullOrEmpty() || !pass.isNullOrEmpty()) {
            val u = enc(user ?: "")
            val p = enc(pass ?: "")
            "$u:$p@"
        } else ""
        return "rtsp://$creds${uri.host}:$port$path"
    }

    private fun enc(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun setStatus(text: String) {
        tvStatus.text = text
        tvStatus.visibility = View.VISIBLE
    }

    override fun onStop() {
        super.onStop()
        cameraPlayer.release()   // risparmio batteria/retim quando in background
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraPlayer.release()
    }
}
