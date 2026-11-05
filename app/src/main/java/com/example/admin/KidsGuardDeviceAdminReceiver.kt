package com.example.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class KidsGuardDeviceAdminReceiver : DeviceAdminReceiver() {
  override fun onEnabled(context: Context, intent: Intent) {
    super.onEnabled(context, intent)
    com.example.util.DeviceSecurityPermissionHelper.setUninstallBlocked(context, true)
    Toast.makeText(context, "Защита от удаления KidsGuard активирована", Toast.LENGTH_SHORT).show()
  }

  override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
    return "Внимание! KidsGuard защищен родительским контролем. Для отключения требуется ввести мастер-пароль родителя в приложении."
  }

  override fun onDisabled(context: Context, intent: Intent) {
    super.onDisabled(context, intent)
    Toast.makeText(context, "Администратор устройства отключен родителем", Toast.LENGTH_SHORT).show()
  }
}
