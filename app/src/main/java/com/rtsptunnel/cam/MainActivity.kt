package com.rtsptunnel.cam

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
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
import com.rtsptunnel.cam.storage.CameraStore
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
    private lateinit var btnSave: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvSavedTitle: TextView
    private lateinit var llSavedList: LinearLayout

    private lateinit var cameraPlayer: CameraPlayer
    private lateinit var cameraStore: CameraStore

    /** Tunnel attivo per la sessione corrente: null = connessione diretta
     *  (consentita solo se l'utente disattiva la modalità privacy). */
    private var tunnelFactory: SocketFactory? = null

    /** Ultime credenziali usate/inserite (non persistite salvo salvataggio esplicito). */
    private var lastUser: String? = null
    private var lastPass: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        playerView = findViewById(R.id.playerView)
        etUrl = findViewById(R.id.etUrl)
        etProxy = findViewById(R.id.etProxy)
        cbRequireProxy = findViewById(R.id.cbRequireProxy)
        btnConnect = findViewById(R.id.btnConnect)
        btnSave = findViewById(R.id.btnSave)
        tvStatus = findViewById(R.id.tvStatus)
        tvSavedTitle = findViewById(R.id.tvSavedTitle)
        llSavedList = findViewById(R.id.llSavedList)

        cameraPlayer = CameraPlayer(this, playerView)
        cameraStore = CameraStore(this)

        btnConnect.setOnClickListener { attemptConnection() }
        btnSave.setOnClickListener { promptSaveCamera() }

        renderSavedCameras()
    }

    // ------------------------------------------------------------------
    // 1) Rilevamento e tentativo automatico (accesso anonimo).
    //    withCreds = true salta il probe e connette direttamente con le
    //    credenziali memorizzate (telecamera salvata con credenziali).
    // ------------------------------------------------------------------
    private fun attemptConnection(withCreds: Boolean = false) {
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

        lifecycleScope.launch(Dispatchers.IO) {
            if (withCreds && (lastUser != null || lastPass != null)) {
                // connessione autenticata diretta (telecamera salvata)
                withContext(Dispatchers.Main) {
                    setStatus("Connessione con credenziali salvate verso ${uri.host}…")
                    play(buildRtspUrl(uri, lastUser, lastPass))
                }
                return@launch
            }

            setStatus("Tentativo anonimo verso ${uri.host}…" +
                    if (tunnel != null) " (via tunnel, il tuo IP è nascosto)" else " (diretta: IP visibile!)")

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
        val etUser = EditText(this).apply {
            hint = "Username"
            setText(lastUser ?: "")
        }
        val etPass = EditText(this).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(lastPass ?: "")
        }
        container.addView(etUser)
        container.addView(etPass)

        AlertDialog.Builder(this)
            .setTitle("Autenticazione telecamera")
            .setMessage("La camera ${uri.host} ha rifiutato l'accesso anonimo (401). Inserisci le credenziali.")
            .setView(container)
            .setPositiveButton("Connetti") { _, _ ->
                // 4) Connessione autenticata: URL rigenerato con le credenziali
                lastUser = etUser.text.toString()
                lastPass = etPass.text.toString()
                play(buildRtspUrl(uri, lastUser, lastPass))
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
        setStatus("Credenziali errate o mancanti (401). Riprova con «Connetti».")
        Toast.makeText(this, "Credenziali rifiutate dalla telecamera", Toast.LENGTH_LONG).show()
    }

    override fun onGenericError(message: String?) {
        setStatus("Errore di riproduzione: ${message ?: "sconosciuto"}")
    }

    // ------------------------------------------------------------------
    // Telecamere salvate (persistenza locale, nessun cloud)
    // ------------------------------------------------------------------
    private fun promptSaveCamera() {
        val raw = etUrl.text.toString().trim()
        if (raw.isEmpty()) {
            setStatus("Inserisci prima l'indirizzo della telecamera")
            return
        }
        val uri = normalizeRtsp(raw) ?: run {
            setStatus("Indirizzo non valido, impossibile salvare")
            return
        }
        val hasCreds = lastUser != null || lastPass != null

        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val etName = EditText(this).apply {
            hint = "Nome (es. Ingresso)"
            setText(uri.host)
        }
        val cbCreds = CheckBox(this).apply {
            text = "Salva anche le credenziali (in chiaro sul telefono)"
            isEnabled = hasCreds
            if (!hasCreds) alpha = 0.5f
        }
        container.addView(etName)
        container.addView(cbCreds)

        AlertDialog.Builder(this)
            .setTitle("Salva telecamera")
            .setView(container)
            .setPositiveButton("Salva") { _, _ ->
                val name = etName.text.toString().trim().ifEmpty { uri.host ?: "Camera" }
                cameraStore.save(
                    name, raw, etProxy.text.toString().trim(),
                    if (cbCreds.isChecked) lastUser else null,
                    if (cbCreds.isChecked) lastPass else null
                )
                renderSavedCameras()
                setStatus("Telecamera «$name» salvata")
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun renderSavedCameras() {
        llSavedList.removeAllViews()
        val cams = cameraStore.list()
        tvSavedTitle.visibility = if (cams.isEmpty()) View.GONE else View.VISIBLE

        for (c in cams) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
            }

            val label = TextView(this).apply {
                text = "${c.name}\n${c.url}" +
                        (if (!c.user.isNullOrEmpty()) "  •  🔑" else "")
                setTextAppearance(androidx.appcompat.R.style.TextAppearance_AppCompat_Medium)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    etUrl.setText(c.url)
                    etProxy.setText(c.proxy)
                    if (!c.user.isNullOrEmpty() || !c.pass.isNullOrEmpty()) {
                        lastUser = c.user
                        lastPass = c.pass
                        attemptConnection(withCreds = true)
                    } else {
                        lastUser = null; lastPass = null
                        attemptConnection()
                    }
                }
            }

            val btnDelete = Button(this).apply {
                text = "✕"
                setOnClickListener {
                    cameraStore.delete(c.id)
                    renderSavedCameras()
                }
            }

            row.addView(label)
            row.addView(btnDelete)
            llSavedList.addView(row)
        }
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
        cameraPlayer.release()   // risparmio batteria/rete quando in background
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraPlayer.release()
    }
}
