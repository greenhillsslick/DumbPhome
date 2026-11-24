package com.dumbos.apps.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.os.BatteryManager
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import com.dumbos.apps.alarm.AlarmActivity
import com.dumbos.apps.mail.MailActivity
import com.dumbos.apps.sms.MessagesActivity
import com.dumbos.core.Alarms
import com.dumbos.core.Bridge
import com.dumbos.core.NotifMode
import com.dumbos.core.Prefs
import com.dumbos.core.Signal
import com.dumbos.core.Sms
import com.dumbos.ui.Dp
import com.dumbos.ui.HRule
import com.dumbos.ui.KEYBAR_H
import com.dumbos.ui.OsActivity
import com.dumbos.ui.PALETTES
import com.dumbos.ui.PixelList
import com.dumbos.ui.PixelText
import com.dumbos.ui.Sz
import com.dumbos.ui.TYPO
import com.dumbos.ui.Theme
import com.dumbos.ui.vcol
import kotlin.math.max

/**
 * Ajustes: everything a 3310 needed (silence, ring, contrast) plus the three
 * dumbOS notification modes:
 *
 *   SILENCIO     everything but calls is quiet, banners are suppressed
 *   NO MOLESTAR  only what you allow gets through
 *   TODO         the phone behaves like a normal smartphone again
 */
class SettingsActivity : OsActivity() {

    private lateinit var list: PixelList
    private var items: List<Item> = emptyList()

    private data class Item(
        val title: String,
        val value: String,
        val kind: Kind = Kind.INFO,
        val action: () -> Unit = {},
    )

    private enum class Kind { INFO, LINK, TOGGLE_ON, CHOICE, SECTION }

    override fun buildLayout() {
        titleText = "AJUSTES"
        setKeys("ABRIR", "MODO RAPIDO")
        showMain()
    }

    private fun rebuild() {
        items = build()
        list.invalidate()
    }

    private fun showMain() {
        titleText = "AJUSTES"
        content.removeAllViews()
        items = build()
        val col = vcol(this)
        content.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val l = PixelList(this, items.size, com.dumbos.ui.Rows.one())
        l.theme = theme
        // Section headers are short rows; without this they overlap the item
        // underneath (the same bug the user reported as "el texto se superpone").
        l.rowHeights = IntArray(items.size) { i ->
            if (items[i].kind == Kind.SECTION) Sz.SMALL.lineH() + Dp.dp(10) else com.dumbos.ui.Rows.one()
        }
        l.paintRow = { canvas, i, sel, rowH ->
            val it0 = items[i]
            val ink = if (sel) theme.selInk else theme.ink
            val dim = if (sel) theme.selInk else theme.dim
            if (it0.kind == Kind.SECTION) {
                TYPO.draw(canvas, it0.title, Dp.dp(6), (rowH - Sz.SMALL.lineH()) / 2, Sz.SMALL, dim)
            } else {
                // Centre the two-line block in the real row height instead of
                // pinning it to 4dp from the top.
                val block = Sz.BODY.lineH() + Dp.dp(1) + Sz.SMALL.lineH()
                val top = ((rowH - block) / 2).coerceAtLeast(0)
                TYPO.drawClipped(canvas, it0.title, Dp.dp(6), top, width - Dp.dp(60), Sz.BODY, ink)
                TYPO.drawRight(canvas, it0.value, width - Dp.dp(8), top + Sz.BODY.lineH() + Dp.dp(1), Sz.SMALL, dim)
                if (it0.kind == Kind.TOGGLE_ON) {
                    TYPO.draw(canvas, "[X]", width - Dp.dp(8) - TYPO.measure(it0.value, Sz.SMALL) - Dp.dp(8),
                        top + Sz.BODY.lineH() + Dp.dp(1), Sz.SMALL, dim)
                }
            }
        }
        l.onChoose = { i -> val it0 = items[i]; if (it0.kind != Kind.SECTION) it0.action() }
        l.onHighlight = { }
        keyTarget = l
        list = l
        col.addView(l, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun build(): List<Item> {
        val p = prefs
        val s = Signal.snapshot(this)
        val items = ArrayList<Item>()
        items.add(Item("SONIDO", NotifMode.label(this), Kind.SECTION))
        items.add(Item("MODO DE NOTIFICACIONES", NotifMode.label(this), Kind.CHOICE) { chooseNotifMode() })
        items.add(Item("TIMBRE / VIBRACION", if (p.sound) "SI" else "NO", if (p.sound) Kind.TOGGLE_ON else Kind.CHOICE) {
            p.sound = !p.sound; applySound(); rebuild()
        })
        items.add(Item("NO MOLESTAR DEL SISTEMA", if (p.dnd) "ACTIVO" else "INACTIVO", Kind.CHOICE) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (!nm.isNotificationPolicyAccessGranted) {
                toast("FALTA ACCESO A NO MOLESTAR")
                startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return@Item
            }
            // Toggling DND is the same knob as the notification mode: off means
            // "normal behaviour", never a silent phone the user did not ask for.
            p.daysMode = if (p.dnd) NotifMode.ALL else NotifMode.DND
            NotifMode.apply(this)
            toast(NotifMode.label(this))
            rebuild()
        })

        items.add(Item("PANTALLA", theme.name, Kind.SECTION))
        items.add(Item("TEMA / COLOR", theme.name, Kind.CHOICE) { chooseTheme() })
        items.add(Item("TEXTO GRANDE", if (p.bigText) "SI" else "NO", if (p.bigText) Kind.TOGGLE_ON else Kind.CHOICE) {
            p.bigText = !p.bigText
            toast(if (p.bigText) "TEXTO GRANDE AL REINICIAR" else "TEXTO NORMAL")
            rebuild()
        })
        items.add(Item("FONDO DE PANTALLA", "MONOCROMO", Kind.INFO) { toast("DUMBOS NO USA FONDOS DE IMAGEN") })

        items.add(Item("DUMBOS", "v0.1", Kind.SECTION))
        items.add(Item("ACCESO A NOTIFICACIONES", if (Bridge.listenerConnected(this)) "CONCEDIDO" else "PENDIENTE", Kind.CHOICE) {
            if (Bridge.listenerConnected(this)) toast("YA CONCEDIDO") else Bridge.requestAccess(this)
        })
        items.add(Item("PERMISOS SMS Y CONTACTOS", permsLabel(), Kind.CHOICE) {
            requestPermissions(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS,
                Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE, Manifest.permission.POST_NOTIFICATIONS), 1)
        })
        items.add(Item("ALARMAS EXACTAS", if (canExact()) "OK" else "CONCEDER", Kind.CHOICE) {
            if (!canExact() && android.os.Build.VERSION.SDK_INT >= 31) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) { toast("NO DISPONIBLE") }
            }
        })
        items.add(Item("OPTIMIZACION DE BATERIA", if (isOptimized()) "LIMITADA" else "LIBRE", Kind.CHOICE) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {}
        })
        items.add(Item("SER EL LAUNCHER", if (isDefaultLauncher()) "SI" else "ELEGIR", Kind.CHOICE) {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
        items.add(Item("PERFIL DE USO", "MINIMO", Kind.INFO) {
            showConfirm("PERFIL MINIMO", "DUMBOS OCULTA LOS AVISOS NO ESENCIALES. EL CONSUMO REAL DEPENDE DE LAS APPS DEL SISTEMA QUE TENGAS ABIERTAS.", {})
        })

        items.add(Item("ESTADO", "TODO", Kind.SECTION))
        items.add(Item("BATERIA", s.battery.toString() + "%", Kind.INFO) {
            val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
            toast("BATERIA " + bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) + "% " + if (s.charging) "CARGANDO" else "")
        })
        items.add(Item("ALARMAS ACTIVAS", Alarms.all(this).count { it.enabled }.toString(), Kind.LINK) {
            startActivity(Intent(this, AlarmActivity::class.java))
        })
        items.add(Item("MENSAJES SIN LEER", Sms.unreadCount(this).toString(), Kind.LINK) {
            startActivity(Intent(this, MessagesActivity::class.java))
        })
        items.add(Item("ACERCA DE DUMBOS", "1.0.6", Kind.INFO) { showAbout() })
        return items
    }

    private fun permsLabel(): String {
        val sms = checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        val contacts = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        return when {
            sms && contacts -> "CONCEDIDOS"
            sms || contacts -> "PARCIALES"
            else -> "PENDIENTES"
        }
    }

    private fun canExact(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 31) return true
        val am = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
        return am.canScheduleExactAlarms()
    }

    private fun isOptimized(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return !pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun isDefaultLauncher(): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val res = packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        return res?.activityInfo?.packageName == packageName
    }

    private fun chooseNotifMode() {
        showMenu("NOTIFICACIONES", listOf(
            "SILENCIO - SOLO LLAMADAS Y ALARMAS",
            "NO MOLESTAR - NADA PASA",
            "TODO - COMPORTAMIENTO NORMAL",
        )) { i ->
            prefs.daysMode = when (i) {
                1 -> NotifMode.DND
                2 -> NotifMode.ALL
                else -> NotifMode.SILENT
            }
            NotifMode.apply(this)
            toast(NotifMode.label(this))
            rebuild()
        }
    }

    private fun chooseTheme() {
        val names = PALETTES.map { it.name + if (it.id == theme.id) " *" else "" }
        showMenu("TEMA", names) { i ->
            val t = PALETTES[i]
            Prefs.setTheme(this, t.id)
            applyTheme(t)
            toast("TEMA " + t.name)
            showMain()
        }
    }

    private fun applySound() {
        val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        if (prefs.sound) am.ringerMode = android.media.AudioManager.RINGER_MODE_NORMAL
        else am.ringerMode = android.media.AudioManager.RINGER_MODE_VIBRATE
    }

    private fun showAbout() {
        val about = """
            DUMBOS 1.0.6
            UN LAUNCHER Y UN ECOSISTEMA
            MONOCROMO PARA ANDROID.

            TODO LO QUE HACE FALTA Y NADA
            DE LO QUE NO.

            TIPOGRAFIA DE MAPA DE PUNTOS
            5X7. ICONOS DE 16X16.

            HECHO EN VALENCIA CON TECLADO
            DE PLASTICO.
        """.trimIndent()
        showConfirm("ACERCA DE", about, {}, {})
    }

    override fun openMenu() {
        showMenu("MODO RAPIDO", listOf("SILENCIO", "NO MOLESTAR", "TODO", "CAMBIAR TEMA")) { i ->
            when (i) {
                0 -> { prefs.daysMode = NotifMode.SILENT; NotifMode.apply(this) }
                1 -> { prefs.daysMode = NotifMode.DND; NotifMode.apply(this) }
                2 -> { prefs.daysMode = NotifMode.ALL; NotifMode.apply(this) }
                3 -> chooseTheme()
            }
            toast(NotifMode.label(this))
            showMain()
        }
    }

    override fun onCreateOs(savedInstanceState: Bundle?) {}
}
