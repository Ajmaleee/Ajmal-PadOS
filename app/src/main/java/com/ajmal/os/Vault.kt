package com.ajmal.os

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.util.Base64
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.math.min

/**
 * Password for the Hidden apps. Only a salted PBKDF2 hash is stored (never the password), comparison is
 * constant-time, and repeated wrong guesses lock entry for a growing delay.
 */
object Vault {
    private const val PREF = "ajmal_vault"
    private const val ITERATIONS = 12000
    const val MIN_LENGTH = 4

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun isSet(ctx: Context): Boolean = guard("vault.isSet", false) { sp(ctx).contains("hash") }

    /** Seconds left of the lock-out after too many wrong attempts (0 = none). */
    fun lockedFor(ctx: Context): Int = guard("vault.locked", 0) {
        val left = ((sp(ctx).getLong("until", 0L) - System.currentTimeMillis()) / 1000L).toInt()
        if (left > 0) left else 0
    }

    fun set(ctx: Context, pw: String): Boolean = guard("vault.set", false) {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        sp(ctx).edit()
            .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("hash", Base64.encodeToString(hash(pw, salt), Base64.NO_WRAP))
            .putInt("fails", 0).putLong("until", 0L)
            .apply()
        true
    }

    fun verify(ctx: Context, pw: String): Boolean = guard("vault.verify", false) {
        if (lockedFor(ctx) > 0) return@guard false
        val s = sp(ctx)
        val salt = Base64.decode(s.getString("salt", "") ?: "", Base64.NO_WRAP)
        val want = Base64.decode(s.getString("hash", "") ?: "", Base64.NO_WRAP)
        val ok = salt.isNotEmpty() && want.isNotEmpty() && MessageDigest.isEqual(hash(pw, salt), want)
        if (ok) {
            s.edit().putInt("fails", 0).putLong("until", 0L).apply()
        } else {
            val fails = s.getInt("fails", 0) + 1
            val e = s.edit().putInt("fails", fails)
            if (fails >= 5) {
                val secs = min(600, 30 * (1 shl min(5, fails - 5)))
                e.putLong("until", System.currentTimeMillis() + secs * 1000L)
            }
            e.apply()
        }
        ok
    }

    fun clear(ctx: Context) { guardRun("vault.clear") { sp(ctx).edit().clear().apply() } }

    private fun hash(pw: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            .generateSecret(PBEKeySpec(pw.toCharArray(), salt, ITERATIONS, 256)).encoded
}

/** The password dialogs. They hide their content from screenshots and the recents screen. */
object VaultUi {
    private fun field(a: Activity, hint: String): EditText {
        val e = EditText(a)
        e.hint = hint
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        e.setSingleLine(true)
        e.setTextColor(Color.WHITE)
        e.setHintTextColor(0xFF8E8E93.toInt())
        return e
    }

    private fun build(a: Activity, title: String, msg: String?, vararg fields: EditText): Pair<AlertDialog, TextView> {
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(a.dpi(20), a.dpi(8), a.dpi(20), 0)
        if (msg != null) box.addView(label(a, msg, 13f, 0xFFB0B0B8.toInt()))
        for (f in fields) box.addView(f, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = a.dpi(8) })
        val err = label(a, "", 13f, 0xFFFF6B6B.toInt())
        box.addView(err, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = a.dpi(8) })
        val d = AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title).setView(box)
            .setPositiveButton("OK", null).setNegativeButton("Cancel", null).create()
        d.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        return d to err
    }

    /** Asks for the password (or creates one the first time) and calls [onOk] only when it is right. */
    fun unlock(a: Activity, onOk: () -> Unit) {
        if (a.isFinishing) return
        if (!Vault.isSet(a)) { create(a) { ok -> if (ok) onOk() }; return }
        val wait = Vault.lockedFor(a)
        if (wait > 0) {
            Toast.makeText(a, "Too many wrong attempts. Try again in $wait s.", Toast.LENGTH_LONG).show()
            return
        }
        val pw = field(a, "Password")
        val (d, err) = build(a, "Hidden apps", "Enter the password to open the hidden apps.", pw)
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val w = Vault.lockedFor(a)
            if (w > 0) { err.text = "Too many wrong attempts. Try again in $w s."; return@setOnClickListener }
            if (Vault.verify(a, pw.text.toString())) { d.dismiss(); onOk() }
            else {
                val w2 = Vault.lockedFor(a)
                err.text = if (w2 > 0) "Too many wrong attempts. Try again in $w2 s." else "Wrong password"
                pw.setText("")
            }
        }
    }

    fun create(a: Activity, done: (Boolean) -> Unit) {
        if (a.isFinishing) return
        val p1 = field(a, "New password (at least ${Vault.MIN_LENGTH} characters)")
        val p2 = field(a, "Repeat password")
        val (d, err) = build(a, "Create a password", "Hidden apps will only open with this password.", p1, p2)
        d.setOnCancelListener { done(false) }
        d.show()
        d.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { d.cancel() }
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val a1 = p1.text.toString()
            when {
                a1.length < Vault.MIN_LENGTH -> err.text = "Use at least ${Vault.MIN_LENGTH} characters"
                a1 != p2.text.toString() -> err.text = "The passwords do not match"
                !Vault.set(a, a1) -> err.text = "Could not save the password"
                else -> { d.dismiss(); Toast.makeText(a, "Password set", Toast.LENGTH_SHORT).show(); done(true) }
            }
        }
    }

    fun change(a: Activity, done: () -> Unit) {
        if (a.isFinishing) return
        val cur = field(a, "Current password")
        val p1 = field(a, "New password")
        val p2 = field(a, "Repeat new password")
        val (d, err) = build(a, "Change password", null, cur, p1, p2)
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val n = p1.text.toString()
            when {
                Vault.lockedFor(a) > 0 -> err.text = "Too many wrong attempts. Try again later."
                !Vault.verify(a, cur.text.toString()) -> err.text = "Current password is wrong"
                n.length < Vault.MIN_LENGTH -> err.text = "Use at least ${Vault.MIN_LENGTH} characters"
                n != p2.text.toString() -> err.text = "The new passwords do not match"
                !Vault.set(a, n) -> err.text = "Could not save the password"
                else -> { d.dismiss(); Toast.makeText(a, "Password changed", Toast.LENGTH_SHORT).show(); done() }
            }
        }
    }

    fun remove(a: Activity, done: () -> Unit) {
        unlock(a) {
            Vault.clear(a)
            Toast.makeText(a, "Password removed", Toast.LENGTH_SHORT).show()
            done()
        }
    }
}
