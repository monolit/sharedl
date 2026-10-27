package app.sharedl

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate

object Loc {
    /** Контекст с языком, выбранным в приложении (для сервиса/уведомлений на Android < 13). */
    fun ctx(c: Context): Context {
        val l = AppCompatDelegate.getApplicationLocales()
        if (l.isEmpty) return c
        val cfg = Configuration(c.resources.configuration)
        cfg.setLocales(LocaleList.forLanguageTags(l.toLanguageTags()))
        return c.createConfigurationContext(cfg)
    }
}
