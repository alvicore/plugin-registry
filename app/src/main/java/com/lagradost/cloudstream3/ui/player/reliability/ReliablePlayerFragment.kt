package com.lagradost.cloudstream3.ui.player.reliability

import android.os.Handler
import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import com.lagradost.api.Log
import com.lagradost.cloudstream3.ui.player.CSPlayerEvent
import com.lagradost.cloudstream3.ui.player.CSPlayerLoading
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.PlayerEventSource
import java.net.SocketTimeoutException

/**
 * FORK. Strato di affidabilita' sopra il player, tutto in un file nuovo: nel core cambiano solo
 * la parola `open` davanti a GeneratorPlayer e il nome della classe nel grafo di navigazione,
 * cosi' gli aggiornamenti da monte non entrano in conflitto con questo codice.
 *
 * Risolve due cose osservate su una connessione domestica e su un Fire TV Stick:
 *
 * 1. Un buco di rete di pochi secondi bruciava una fonte buona: qualunque errore passava dritto
 *    al mirror successivo, e con una o due fonti per episodio si finiva su "nessun link trovato"
 *    per un problema che sarebbe passato da solo.
 *
 * 2. Un blocco in caricamento non finiva mai: ExoPlayer resta in buffering senza alcun timer, e
 *    su un errore di rete con durata nota CS3IPlayer richiama prepare() in un ciclo silenzioso.
 *    Lo spinner girava all'infinito e l'unica via d'uscita era il telecomando.
 *
 * Tre regole imparate dalla revisione, che spiegano quasi tutto il codice qui sotto:
 *
 * - **Un recupero e' legato al contenuto per cui e' nato.** Cambio episodio, cambio mirror o
 *   player ricostruito annullano tutto: senza questo, un tentativo in coda faceva ripartire
 *   l'episodio precedente sopra quello nuovo.
 * - **Non si toglie mai la pausa a chi guarda.** Nel core i pannelli (fonti, tracce, episodi)
 *   mettono in pausa il player: riemettere Play alla cieca riaccendeva l'audio dietro a un
 *   dialogo aperto. Il player si ricostruisce comunque, ma riparte solo se stava andando.
 * - **Un errore non si perde mai.** Se il tentativo non arriva a partire (schermo spento, uscita
 *   dalla schermata), l'errore viene conservato e consegnato al core al ritorno, altrimenti il
 *   video resterebbe fermo in silenzio, senza messaggio e senza cambio di fonte.
 */
class ReliablePlayerFragment : GeneratorPlayer() {

    private val handler = Handler(Looper.getMainLooper())

    private var retriesLeft = MAX_RETRIES
    private var failedAt = NO_POSITION

    /** Un solo timer per tipo puo' essere in volo: si tengono per poterli togliere davvero. */
    private var pendingRetry: Runnable? = null
    private var pendingWatchdog: Runnable? = null

    /** Errore che il core non ha ancora visto perche' stavamo tentando il recupero. */
    private var swallowedError: Throwable? = null

    private var bufferedWhenArmed = NO_POSITION
    private var rearmCount = 0

    // --- il contenuto cambia: tutto quello che era in volo non vale piu' -----------------

    override fun nextEpisode() {
        resetForNewContent()
        super.nextEpisode()
    }

    override fun prevEpisode() {
        resetForNewContent()
        super.prevEpisode()
    }

    override fun nextMirror() {
        resetForNewContent()
        super.nextMirror()
    }

    override fun playerUpdated(player: Any?) {
        // Il player e' stato ricostruito (anche dal nostro recupero): i timer si riferivano a
        // un'istanza che non esiste piu'. Il budget invece non si tocca, o il recupero
        // diventerebbe infinito.
        cancelTimers()
        super.playerUpdated(player)
    }

    // --- stato e progresso ----------------------------------------------------------------

    override fun playerPositionChanged(position: Long, duration: Long) {
        super.playerPositionChanged(position, duration)
        // Il budget torna pieno solo dopo aver superato di un minuto il punto del guasto: se il
        // video e' rotto sempre nello stesso segmento, ritentare all'infinito non aiuta e ruba
        // tempo al mirror. Qui la posizione arriva di continuo, mentre i cambi di stato no.
        if (failedAt != NO_POSITION && position > failedAt + PROGRESS_MS) {
            retriesLeft = MAX_RETRIES
            failedAt = NO_POSITION
        }
    }

    override fun playerStatusChanged() {
        super.playerStatusChanged()
        updateWatchdog(currentPlayerStatus)
    }

    override fun onResume() {
        super.onResume()
        // Al rientro (da PiP, da background, dal salvaschermo) lo stato corrente va rivalutato:
        // la transizione che avrebbe armato la sorveglianza puo' essere gia' passata.
        deliverSwallowedError()
        updateWatchdog(currentPlayerStatus)
    }

    override fun playerError(exception: Throwable) {
        if (tryRecover(exception, "errore: ${exception::class.simpleName}")) return
        cancelTimers()
        swallowedError = null
        super.playerError(exception)
    }

    override fun onStop() {
        // In background non si tocca niente: far ripartire l'audio a schermo spento sarebbe
        // peggio del problema che stiamo risolvendo. L'errore resta in attesa per il rientro.
        cancelTimers()
        super.onStop()
    }

    override fun onDestroy() {
        cancelTimers()
        swallowedError = null
        super.onDestroy()
    }

    // --- riavvio dello stesso link --------------------------------------------------------

    private fun tryRecover(exception: Throwable?, reason: String): Boolean {
        if (retriesLeft <= 0) return false
        if (exception != null && !isTransient(exception)) return false
        if (!isResumed || !player.isActive()) return false

        cancelTimers()
        failedAt = player.getPosition() ?: NO_POSITION
        swallowedError = exception
        val attempt = MAX_RETRIES - retriesLeft
        Log.i(TAG, "$reason -> riprovo lo stesso link (${attempt + 1}/$MAX_RETRIES) da ${failedAt}ms")

        val runnable = Runnable {
            pendingRetry = null
            if (!isResumed || view == null || !player.isActive()) {
                // Il tentativo non e' potuto partire: il core deve sapere che c'e' stato un
                // errore, altrimenti il video resta fermo senza messaggio ne' cambio di fonte.
                Log.i(TAG, "tentativo non eseguibile: restituisco l'errore al player")
                deliverSwallowedError()
                return@Runnable
            }
            val ctx = context ?: return@Runnable
            retriesLeft--
            swallowedError = null

            // Chi guarda puo' aver messo in pausa nel frattempo, e i pannelli del core (fonti,
            // tracce, episodi) mettono in pausa anche loro: il player si ricostruisce comunque,
            // ma riparte solo se stava andando.
            val wasPlaying = playerView?.player?.playWhenReady == true
            player.saveData()
            player.reloadPlayer(ctx)
            if (wasPlaying) {
                player.handleEvent(CSPlayerEvent.Play, PlayerEventSource.Player)
            } else {
                Log.i(TAG, "ricaricato ma lasciato in pausa: non stava riproducendo")
            }
        }
        pendingRetry = runnable
        handler.postDelayed(runnable, BACKOFF_MS[attempt.coerceAtMost(BACKOFF_MS.size - 1)])
        return true
    }

    /** Consegna al core un errore che avevamo trattenuto per tentare il recupero. */
    private fun deliverSwallowedError() {
        val pending = swallowedError ?: return
        swallowedError = null
        retriesLeft = MAX_RETRIES
        failedAt = NO_POSITION
        super.playerError(pending)
    }

    @OptIn(UnstableApi::class)
    private fun isTransient(throwable: Throwable): Boolean {
        if (throwable is StallException || throwable is SocketTimeoutException) return true

        // Un codice HTTP definitivo (403, 404, 410) non migliora aspettando: meglio cambiare
        // fonte subito. La causa puo' essere annidata sotto piu' livelli di loader.
        val httpCode = generateSequence(throwable) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()?.responseCode
        if (httpCode != null) return httpCode in TRANSIENT_HTTP

        val playback = throwable as? PlaybackException ?: return false
        return playback.errorCode in TRANSIENT_PLAYBACK
    }

    // --- sorveglianza dei blocchi in caricamento ------------------------------------------

    private fun updateWatchdog(status: CSPlayerLoading) {
        // La pausa si riconosce da playWhenReady, non dallo stato: dopo un errore di rete
        // ExoPlayer riporta IsPaused mentre ritenta da solo, ed e' proprio il ciclo che
        // vogliamo limitare. Con playWhenReady a false invece la pausa e' voluta.
        val wantsToPlay = playerView?.player?.playWhenReady != false
        when {
            !wantsToPlay -> cancelWatchdog()
            status == CSPlayerLoading.IsBuffering -> armWatchdog()
            status == CSPlayerLoading.IsPlaying || status == CSPlayerLoading.IsEnded -> cancelWatchdog()
        }
    }

    private fun armWatchdog() {
        if (pendingWatchdog != null || !isResumed) return
        bufferedWhenArmed = playerView?.player?.bufferedPosition ?: NO_POSITION
        // Prima del primo fotogramma la durata non e' nota e c'e' da aprire playlist e primo
        // segmento: si concede piu' tempo, altrimenti su una rete lenta si scambia un avvio
        // faticoso per un blocco.
        val timeout = if ((player.getDuration() ?: 0L) <= 0L) FIRST_LOAD_STALL_MS else STALL_MS

        val runnable = Runnable {
            pendingWatchdog = null
            if (!isResumed || currentPlayerStatus != CSPlayerLoading.IsBuffering) return@Runnable
            if (playerView?.player?.playWhenReady == false) return@Runnable

            // Se il buffer e' cresciuto di qualcosa di utile i dati arrivano, solo piano: si
            // concede un altro giro invece di buttare via quello che e' stato scaricato. Ma non
            // all'infinito: pochi byte al secondo non faranno mai ripartire il video.
            val buffered = playerView?.player?.bufferedPosition ?: NO_POSITION
            val grown = buffered - bufferedWhenArmed
            if (grown >= MIN_USEFUL_GROWTH_MS && rearmCount < MAX_REARMS) {
                rearmCount++
                Log.i(TAG, "caricamento lento ma vivo (+${grown}ms), aspetto ancora ($rearmCount/$MAX_REARMS)")
                armWatchdog()
                return@Runnable
            }

            rearmCount = 0
            val stall = StallException("Nessun dato utile da ${timeout / 1000} secondi")
            if (tryRecover(stall, "blocco in caricamento")) return@Runnable
            // Esauriti i tentativi si passa dalla strada del core: marca il link come guasto,
            // avvisa e sceglie il mirror successivo o esce. Farlo a mano lasciava il link
            // guasto tra quelli buoni.
            Log.w(TAG, "blocco in caricamento non recuperato: lascio decidere al player")
            cancelTimers()
            swallowedError = null
            super.playerError(stall)
        }
        pendingWatchdog = runnable
        handler.postDelayed(runnable, timeout)
    }

    private fun cancelWatchdog() {
        pendingWatchdog?.let { handler.removeCallbacks(it) }
        pendingWatchdog = null
        rearmCount = 0
    }

    private fun cancelTimers() {
        cancelWatchdog()
        pendingRetry?.let { handler.removeCallbacks(it) }
        pendingRetry = null
    }

    private fun resetForNewContent() {
        cancelTimers()
        swallowedError = null
        retriesLeft = MAX_RETRIES
        failedAt = NO_POSITION
    }

    companion object {
        private const val TAG = "ReliablePlayer"
        private const val NO_POSITION = -1L
        private const val MAX_RETRIES = 2
        private val BACKOFF_MS = longArrayOf(1_500L, 4_000L)

        /** Quanto va riprodotto oltre il punto del guasto perche' si consideri superato. */
        private const val PROGRESS_MS = 60_000L

        private const val STALL_MS = 25_000L
        private const val FIRST_LOAD_STALL_MS = 40_000L

        /** Sotto questa crescita il buffer non ripartira' mai: e' un blocco, non lentezza. */
        private const val MIN_USEFUL_GROWTH_MS = 3_000L
        private const val MAX_REARMS = 3

        private val TRANSIENT_HTTP = setOf(408, 425, 429, 500, 502, 503, 504)
        private val TRANSIENT_PLAYBACK = setOf(
            PlaybackException.ERROR_CODE_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        )
    }
}

/** ErrorLoadingException e' final, quindi il blocco in caricamento ha la sua eccezione. */
class StallException(message: String) : Exception(message)
