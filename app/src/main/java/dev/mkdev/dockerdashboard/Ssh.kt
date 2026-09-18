package dev.mkdev.dockerdashboard

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import kotlinx.coroutines.flow.first
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secours par SSH, sans passer par le dashboard (il peut être mort, Docker
 * aussi : seul sshd du NAS doit répondre).
 *
 * - Une paire de clés ECDSA P-256 générée dans l'app ; la clé privée (PEM)
 *   est chiffrée AES-GCM par une clé du Keystore Android (jamais en clair,
 *   jamais exportée). La clé publique est à coller une fois dans
 *   `~/.ssh/authorized_keys` du NAS.
 * - Empreinte du serveur mémorisée au premier contact (TOFU) ; si elle
 *   change, on refuse et on le dit.
 * - Le script de secours est envoyé sur l'entrée standard de `bash -s` :
 *   rien à installer sur le NAS.
 */
object Ssh {
    private const val KEYSTORE_ALIAS = "dd_ssh_wrap"
    private val SSH_PRIVATE = stringPreferencesKey("ssh_private_enc") // base64(iv) + ":" + base64(ciphertext)
    private val SSH_PUBLIC = stringPreferencesKey("ssh_public")        // "ecdsa-sha2-nistp256 AAAA… docker-dashboard-app"
    private val KNOWN_HOSTS = stringPreferencesKey("ssh_known_hosts")  // format known_hosts, une ligne par hôte

    // ── Clés ──────────────────────────────────────────────

    private fun wrapKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEYSTORE_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrapKey()) }
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(c.doFinal(plain), Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): ByteArray {
        val (iv, data) = stored.split(":", limit = 2)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))) }
        return c.doFinal(Base64.decode(data, Base64.NO_WRAP))
    }

    /** Clé publique OpenSSH (générée à la première demande). */
    suspend fun publicKey(context: Context): String {
        context.dataStore.data.first()[SSH_PUBLIC]?.let { if (it.isNotBlank()) return it }
        val kp = KeyPair.genKeyPair(JSch(), KeyPair.ECDSA, 256)
        val prv = ByteArrayOutputStream().also { kp.writePrivateKey(it) }.toByteArray()
        val pub = ByteArrayOutputStream().also { kp.writePublicKey(it, "docker-dashboard-app") }.toString().trim()
        kp.dispose()
        context.dataStore.edit { it[SSH_PRIVATE] = encrypt(prv); it[SSH_PUBLIC] = pub }
        return pub
    }

    /** Nouvelle paire (l'ancienne clé publique cesse d'ouvrir les NAS). */
    suspend fun regenerate(context: Context): String {
        context.dataStore.edit { it.remove(SSH_PRIVATE); it.remove(SSH_PUBLIC) }
        return publicKey(context)
    }

    suspend fun forgetHost(context: Context, host: String) {
        context.dataStore.edit { p -> p[KNOWN_HOSTS] = (p[KNOWN_HOSTS] ?: "").lines().filterNot { it.startsWith("$host ") || it.startsWith("[$host]:") }.joinToString("\n") }
    }

    // ── Diagnostic ────────────────────────────────────────

    /** Un port TCP répond-il ? (2 s) */
    fun tcpOpen(host: String, port: Int, timeoutMs: Int = 2000): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true }
    } catch (_: Exception) { false }

    // ── Exécution ─────────────────────────────────────────

    class HostKeyChanged(host: String) : Exception("L'empreinte SSH de $host a changé : refus par sécurité. Si c'est normal (réinstallation), oublie l'hôte dans la fiche du dashboard.")

    /**
     * Exécute `script` via `bash -s` sur le NAS ; chaque ligne de sortie (stdout+stderr
     * mélangés) passe par [onLine]. → code de sortie.
     */
    suspend fun run(context: Context, host: String, port: Int, user: String, script: String, onLine: (String) -> Unit): Int {
        val prefs = context.dataStore.data.first()
        val prv = prefs[SSH_PRIVATE] ?: error("Pas de clé SSH : ouvre la fiche du dashboard pour en générer une.")
        val pub = prefs[SSH_PUBLIC] ?: ""
        val known = prefs[KNOWN_HOSTS] ?: ""
        val hostPattern = if (port == 22) host else "[$host]:$port"
        val knownLine = known.lines().firstOrNull { it.startsWith("$hostPattern ") }

        val jsch = JSch()
        jsch.addIdentity("docker-dashboard-app", decrypt(prv), pub.toByteArray(), null)
        if (knownLine != null) jsch.setKnownHosts(known.byteInputStream())
        val session: Session = jsch.getSession(user, host, port).apply {
            setConfig("StrictHostKeyChecking", if (knownLine != null) "yes" else "no")
            setConfig("PreferredAuthentications", "publickey")
            timeout = 20_000
        }
        try {
            try { session.connect(10_000) } catch (e: com.jcraft.jsch.JSchException) {
                if (knownLine != null && e.message?.contains("HostKey", ignoreCase = true) == true) throw HostKeyChanged(host)
                if (e.message?.contains("Auth", ignoreCase = true) == true) error("Authentification refusée : la clé publique de l'app est-elle dans ~/.ssh/authorized_keys de $user@$host ?")
                throw e
            }
            if (knownLine == null) {
                val hk: HostKey = session.hostKey
                context.dataStore.edit { p -> p[KNOWN_HOSTS] = ((p[KNOWN_HOSTS] ?: "").lines().filter { it.isNotBlank() } + "$hostPattern ${hk.type} ${hk.key}").joinToString("\n") }
                onLine("🔐 Empreinte de $host mémorisée (${hk.getFingerPrint(jsch)})")
            }
            val ch = session.openChannel("exec") as ChannelExec
            ch.setCommand("bash -s")
            ch.setPty(false)
            val out = java.io.PipedInputStream()
            val sink = java.io.PipedOutputStream(out)
            ch.outputStream = sink
            ch.setErrStream(sink, true)
            ch.setInputStream(script.byteInputStream())
            ch.connect(10_000)
            out.bufferedReader().useLines { lines -> lines.forEach { onLine(it) } }
            while (!ch.isClosed) Thread.sleep(50)
            val code = ch.exitStatus
            ch.disconnect()
            return code
        } finally { session.disconnect() }
    }

    // ── Scripts ───────────────────────────────────────────

    /** Remet Docker puis le conteneur du dashboard sur pied ; parle français ; sort 0 si tout va bien. */
    fun rescueScript(containerName: String = "docker-dashboard"): String = """
set -u
say(){ echo "▶ ${'$'}*"; }
SUDO=""; [ "${'$'}(id -u)" != 0 ] && SUDO="sudo -n"
say "Hôte : ${'$'}(hostname) — ${'$'}(uptime -p 2>/dev/null || uptime)"
if docker info >/dev/null 2>&1; then
  say "Docker répond"
else
  say "Docker ne répond pas → redémarrage du service"
  if ! ( ${'$'}SUDO systemctl restart docker 2>&1 || ${'$'}SUDO service docker restart 2>&1 ); then
    echo "✗ Impossible de redémarrer Docker (sudo sans mot de passe pour ${'$'}(id -un) ? voir la fiche du dashboard)"; exit 2
  fi
  for i in ${'$'}(seq 1 20); do docker info >/dev/null 2>&1 && break; sleep 2; done
  docker info >/dev/null 2>&1 && say "Docker redémarré" || { echo "✗ Docker toujours KO : ${'$'}(${'$'}SUDO journalctl -u docker -n 5 --no-pager 2>/dev/null | tail -3)"; exit 3; }
fi
NAME="$containerName"
ST=${'$'}(docker inspect -f '{{.State.Status}}' "${'$'}NAME" 2>/dev/null || echo absent)
say "Conteneur ${'$'}NAME : ${'$'}ST"
if [ "${'$'}ST" = absent ]; then
  for P in "${'$'}{NAME}__failed" "${'$'}{NAME}_old_" "${'$'}{NAME}__next"; do
    C=${'$'}(docker ps -a --filter "name=^/${'$'}P" --format '{{.Names}}' | head -1)
    [ -n "${'$'}C" ] && { say "Reprise de ${'$'}C → ${'$'}NAME"; docker rename "${'$'}C" "${'$'}NAME" && break; }
  done
  ST=${'$'}(docker inspect -f '{{.State.Status}}' "${'$'}NAME" 2>/dev/null || echo absent)
fi
[ "${'$'}ST" = absent ] && { echo "✗ Aucun conteneur ${'$'}NAME : à recréer (compose / Portainer)"; exit 4; }
if [ "${'$'}ST" != running ]; then say "Démarrage de ${'$'}NAME"; docker start "${'$'}NAME" 2>&1 || { echo "✗ docker start a échoué"; exit 5; }; fi
for i in ${'$'}(seq 1 20); do
  S=${'$'}(docker inspect -f '{{.State.Status}}' "${'$'}NAME"); H=${'$'}(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "${'$'}NAME")
  [ "${'$'}S" = running ] && { [ -z "${'$'}H" ] || [ "${'$'}H" = healthy ]; } && break; sleep 2
done
say "État final : ${'$'}(docker inspect -f '{{.State.Status}} {{if .State.Health}}({{.State.Health.Status}}){{end}}' "${'$'}NAME")"
EX=${'$'}(docker ps -a --filter status=exited --format '  {{.Names}} — {{.Status}}' | head -15)
[ -n "${'$'}EX" ] && { say "Conteneurs arrêtés :"; echo "${'$'}EX"; }
[ "${'$'}(docker inspect -f '{{.State.Status}}' "${'$'}NAME")" = running ] && exit 0 || exit 6
"""

    fun rebootScript(): String = """
SUDO=""; [ "${'$'}(id -u)" != 0 ] && SUDO="sudo -n"
echo "▶ Redémarrage de ${'$'}(hostname)…"
${'$'}SUDO systemctl reboot 2>&1 || ${'$'}SUDO reboot 2>&1 || { echo "✗ Impossible de redémarrer (sudo sans mot de passe ?)"; exit 2; }
"""

    /** La ligne sudoers à donner à l'utilisateur non-root. */
    fun sudoersHint(user: String) = "$user ALL=(root) NOPASSWD: /bin/systemctl restart docker, /usr/sbin/service docker restart, /bin/systemctl reboot, /sbin/reboot, /bin/journalctl"
}
