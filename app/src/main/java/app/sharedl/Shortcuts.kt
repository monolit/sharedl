package app.sharedl

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

object Shortcuts {
    /** Динамический ярлык с категорией share-target — по нему система показывает ShareDL в ряду Direct Share. */
    fun publish(ctx: Context) {
        try {
            val s = ShortcutInfoCompat.Builder(ctx, "sharedl_download")
                .setShortLabel(ctx.getString(R.string.app_name))
                .setIcon(IconCompat.createWithResource(ctx, R.mipmap.ic_launcher))
                .setIntent(Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW))
                .setCategories(setOf("app.sharedl.category.DOWNLOAD"))
                .setLongLived(true)
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(ctx, s)
        } catch (_: Exception) {
        }
    }
}
