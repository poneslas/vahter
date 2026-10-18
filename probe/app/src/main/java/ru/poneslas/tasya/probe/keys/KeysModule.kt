package ru.poneslas.tasya.probe.keys

import android.content.Context
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import ru.poneslas.tasya.probe.Module
import ru.poneslas.tasya.probe.assist.Sheet
import ru.poneslas.tasya.probe.core.Probe
import ru.poneslas.tasya.probe.core.ProbeScope
import ru.poneslas.tasya.probe.core.Status
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

object KeysModule : Module {
    override val probes = listOf(KeystoreProbe, KeystoreLockedProbe)
}

/**
 * Допущения `Архитектура.md` про Keystore: ключ API хранится зашифрованным AES из Keystore и
 * расшифровывается на заблокированном телефоне (лист работает поверх блокировки); подпись EC P-256
 * для Hermes тоже нужна на блокировке; ключи переживают самообновление.
 */
private const val AES = "p25_aes"                 // как в продукте: без аутентификации и без unlockedDeviceRequired
private const val AES_UNLOCKED = "p25_aes_unlocked" // для сравнения: setUnlockedDeviceRequired(true)
private const val EC = "p25_ec"
private const val EC_SB = "p25_ec_strongbox"
private const val SECRET = "sk-test-0123456789abcdef"

private fun ks() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
private fun prefs(ctx: Context) = ctx.getSharedPreferences("p25", Context.MODE_PRIVATE)

private fun aesKey(alias: String, unlocked: Boolean): SecretKey {
    (ks().getKey(alias, null) as? SecretKey)?.let { return it }
    val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
    g.init(
        KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUnlockedDeviceRequired(unlocked)
            .build(),
    )
    return g.generateKey()
}

private fun ecKey(alias: String, strongBox: Boolean): PrivateKey {
    (ks().getKey(alias, null) as? PrivateKey)?.let { return it }
    val g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
    g.initialize(
        KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setIsStrongBoxBacked(strongBox)
            .build(),
    )
    return g.generateKeyPair().private
}

private fun encrypt(k: SecretKey, plain: String): String {
    val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, k) }
    val ct = c.doFinal(plain.toByteArray())
    return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
}

private fun decrypt(k: SecretKey, blob: String): String {
    val (iv, ct) = blob.split(":").map { Base64.decode(it, Base64.NO_WRAP) }
    val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, iv)) }
    return String(c.doFinal(ct))
}

private fun securityLevel(k: SecretKey): String = runCatching {
    val info = SecretKeyFactory.getInstance(k.algorithm, "AndroidKeyStore").getKeySpec(k, KeyInfo::class.java) as KeyInfo
    levelName(info.securityLevel)
}.getOrElse { "? ${it.javaClass.simpleName}" }

private fun securityLevel(k: PrivateKey): String = runCatching {
    val info = java.security.KeyFactory.getInstance(k.algorithm, "AndroidKeyStore").getKeySpec(k, KeyInfo::class.java)
    levelName(info.securityLevel)
}.getOrElse { "? ${it.javaClass.simpleName}" }

private fun levelName(l: Int) = when (l) {
    KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox"
    KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TEE"
    KeyProperties.SECURITY_LEVEL_SOFTWARE -> "программный"
    else -> "уровень $l"
}

/** Расшифровать оба AES-ключа и подписать обоими EC; результат — строка для лога и признак успеха. */
private fun ProbeScope.useAll(tag: String): Map<String, Boolean> {
    val p = prefs(ctx)
    val out = linkedMapOf<String, Boolean>()
    for ((alias, unlocked) in listOf(AES to false, AES_UNLOCKED to true)) {
        val blob = p.getString(alias, null) ?: continue
        val t = SystemClock.elapsedRealtimeNanos()
        val ok = runCatching { decrypt(aesKey(alias, unlocked), blob) == SECRET }
            .onFailure { error(it, "$tag: расшифровка $alias") }.getOrDefault(false)
        metric("${tag}_${alias}_decrypt_ms", (SystemClock.elapsedRealtimeNanos() - t) / 1e6)
        log(tag, "$alias расшифровка: ${if (ok) "да" else "нет"}")
        out[alias] = ok
    }
    for ((alias, sb) in listOf(EC to false, EC_SB to true)) {
        if (ks().getKey(alias, null) == null) continue
        val t = SystemClock.elapsedRealtimeNanos()
        val ok = runCatching {
            Signature.getInstance("SHA256withECDSA").run { initSign(ecKey(alias, sb)); update("probe".toByteArray()); sign() }
            true
        }.onFailure { error(it, "$tag: подпись $alias") }.getOrDefault(false)
        metric("${tag}_${alias}_sign_ms", (SystemClock.elapsedRealtimeNanos() - t) / 1e6)
        log(tag, "$alias подпись: ${if (ok) "да" else "нет"}")
        out[alias] = ok
    }
    return out
}

object KeystoreProbe : Probe(
    "П25", "Keystore: шифрование ключа API, подпись, переживает обновление",
    "AES-ключ без аутентификации расшифровывает ключ API; EC P-256 подписывает (с StrongBox и без); ключи и шифртекст переживают обновление APK.",
    needsHuman = false,
    steps = listOf(
        "Пробник создаёт ключи (если их нет), шифрует тестовую строку, мерит расшифровку и 20 подписей.",
        "После обновления (файл tasya-probe-2.apk или scripts/bump.sh 1) запустите П25 снова: ключи, созданные прежней версией, должны расшифровать прежний шифртекст.",
        "Блокировка — П25ч.",
    ),
) {
    override suspend fun ProbeScope.run(): Status {
        val p = prefs(ctx)
        val vc = ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
        val createdAt = p.getLong("created_vc", -1)
        if (createdAt >= 0) log("keys", "ключи созданы версией $createdAt, сейчас $vc")

        // Ключи и шифртекст создаются один раз — так второй запуск после обновления проверяет их сохранность.
        for ((alias, unlocked) in listOf(AES to false, AES_UNLOCKED to true)) {
            val t = SystemClock.elapsedRealtimeNanos()
            val k = runCatching { aesKey(alias, unlocked) }.onFailure { error(it, "создание $alias") }.getOrNull() ?: continue
            metric("${alias}_get_or_create_ms", (SystemClock.elapsedRealtimeNanos() - t) / 1e6)
            log("level", "$alias: ${securityLevel(k)}")
            if (p.getString(alias, null) == null) p.edit { putString(alias, encrypt(k, SECRET)) }
        }
        var strongBox = "нет"
        for ((alias, sb) in listOf(EC to false, EC_SB to true)) {
            val k = runCatching { ecKey(alias, sb) }.onFailure {
                log("ec", "$alias: ${it.javaClass.simpleName} ${it.message}")
            }.getOrNull() ?: continue
            log("level", "$alias: ${securityLevel(k)}")
            if (sb) strongBox = securityLevel(k)
            val times = (1..20).map {
                val t = SystemClock.elapsedRealtimeNanos()
                Signature.getInstance("SHA256withECDSA").run { initSign(k); update("probe-$it".toByteArray()); sign() }
                (SystemClock.elapsedRealtimeNanos() - t) / 1e6
            }
            times.forEach { metric("${alias}_sign_ms", it) }
        }
        if (createdAt < 0) p.edit { putLong("created_vc", vc) }

        val used = useAll("unlocked")
        val survived = createdAt in 0 until vc
        summary(buildString {
            append("AES без аутентификации: ${if (used[AES] == true) "расшифровывает" else "НЕ расшифровывает"}; ")
            append("StrongBox: $strongBox; ")
            append(
                when {
                    survived && used.values.all { it } -> "ключи версии $createdAt работают в версии $vc"
                    survived -> "после обновления $createdAt → $vc что-то не работает"
                    else -> "обновление ещё не проверено — установите tasya-probe-2.apk и снова П25"
                },
            )
        })
        return when {
            used[AES] != true -> Status.FAIL
            survived && used.values.all { it } -> Status.PASS
            else -> Status.PARTIAL
        }
    }
}

object KeystoreLockedProbe : Probe(
    "П25ч", "Keystore на заблокированном телефоне",
    "Ключ API расшифровывается и подпись делается из листа поверх блокировки; ключ с unlockedDeviceRequired — нет (для сравнения).",
    needsHuman = true,
    steps = listOf(
        "Сначала один раз П25 (создаёт ключи).",
        "Заблокируйте телефон, подождите 10 с, удержите боковую кнопку — лист поверх блокировки.",
        "Перезагрузка: не разблокируя, удержите кнопку — откроется ли лист до первой разблокировки (приложение не direct-boot-aware [?]).",
    ),
) {
    override suspend fun ProbeScope.run(): Status {
        if (prefs(ctx).getString(AES, null) == null) {
            summary("Сначала запустите П25 — она создаёт ключи")
            return Status.FAIL
        }
        tell("Заблокируйте телефон, подождите 10 с, затем удержите боковую кнопку. Пробник сам проверит ключи в момент открытия листа на блокировке (до 3 минут).", "Понятно")
        val rec = withTimeoutOrNull(180_000) { Sheet.opens.filter { it.locked }.first() }
        if (rec == null) {
            summary("Лист на блокировке не открылся за 3 минуты")
            return Status.FAIL
        }
        log("locked_open", rec.describe())
        val used = useAll("locked")
        val bfu = ask("После перезагрузки, до первой разблокировки: открылся ли лист по кнопке? (можно проверить позже)", listOf("Открылся", "Не открылся", "Не проверял"))
        val apiOk = used[AES] == true
        summary(buildString {
            append("на блокировке: ключ API ${if (apiOk) "расшифрован" else "НЕ расшифрован"}")
            append(", unlockedDeviceRequired-ключ: ${if (used[AES_UNLOCKED] == true) "расшифрован (неожиданно)" else "не расшифрован (ожидаемо)"}")
            append(", подпись EC: ${if (used[EC] == true) "да" else "нет"}")
            used[EC_SB]?.let { append(", StrongBox: ${if (it) "да" else "нет"}") }
            append("; до первой разблокировки: $bfu")
        })
        return if (apiOk && used[EC] != false) Status.PASS else Status.FAIL
    }
}
