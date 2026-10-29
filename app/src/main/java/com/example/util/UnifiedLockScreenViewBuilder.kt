package com.example.util

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Создаёт единую статическую форму блокировки экрана (без отдельных фоновых окон и без дёргания).
 * На этой же форме расположены:
 * 1) Кнопка «🔓 РАЗБЛОКИРОВАТЬ ЭКРАН» со встроенной цифровой клавиатурой PIN-кода (по умолчанию 1234).
 * 2) Кнопка «🚨 ЭКСТРЕННАЯ СВЯЗЬ SOS», которая мгновенно отправляет тревогу, сообщение и GPS-координаты родителю.
 */
object UnifiedLockScreenViewBuilder {

  private fun dp(context: Context, value: Int): Int {
    return TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP,
      value.toFloat(),
      context.resources.displayMetrics
    ).toInt()
  }

  private fun roundedBg(fillHex: String, strokeHex: String? = null, strokeWidthDp: Int = 1, radiusDp: Float = 16f, context: Context): GradientDrawable {
    return GradientDrawable().apply {
      shape = GradientDrawable.RECTANGLE
      cornerRadius = dp(context, radiusDp.toInt()).toFloat()
      setColor(Color.parseColor(fillHex))
      if (strokeHex != null) {
        setStroke(dp(context, strokeWidthDp), Color.parseColor(strokeHex))
      }
    }
  }

  fun buildLockView(
    context: Context,
    reasonText: String,
    isDeviceTotalLock: Boolean,
    onCloseToHome: (() -> Unit)? = null,
    onUnlocked: () -> Unit
  ): View {
    var currentPin = ""

    val rootLayout = object : LinearLayout(context) {
      override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event?.keyCode == KeyEvent.KEYCODE_BACK ||
            event?.keyCode == KeyEvent.KEYCODE_HOME ||
            event?.keyCode == KeyEvent.KEYCODE_APP_SWITCH) {
          return true
        }
        return super.dispatchKeyEvent(event)
      }
    }.apply {
      orientation = LinearLayout.VERTICAL
      // 100% opaque dark slate background so nothing behind it can ever flicker or show through
      setBackgroundColor(Color.parseColor("#FF0B1120"))
      gravity = Gravity.CENTER
      setPadding(dp(context, 22), dp(context, 24), dp(context, 22), dp(context, 24))
      isFocusable = true
      isFocusableInTouchMode = true
    }

    val cardLayout = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER_HORIZONTAL
      background = roundedBg("#FF162038", "#FF38BDF8", 2, 24f, context)
      setPadding(dp(context, 20), dp(context, 22), dp(context, 20), dp(context, 22))
      layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
      )
    }

    val iconText = TextView(context).apply {
      text = if (isDeviceTotalLock) "🔒" else "🛡️"
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 42f)
      gravity = Gravity.CENTER
    }

    val titleView = TextView(context).apply {
      text = if (isDeviceTotalLock) "ЭКРАН ЗАБЛОКИРОВАН" else "ДОСТУП ОГРАНИЧЕН"
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
      setTextColor(Color.parseColor(if (isDeviceTotalLock) "#FFF43F5E" else "#FF38BDF8"))
      gravity = Gravity.CENTER
      typeface = Typeface.DEFAULT_BOLD
      setPadding(0, dp(context, 8), 0, dp(context, 6))
    }

    val descView = TextView(context).apply {
      id = android.R.id.text1
      text = reasonText
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
      setTextColor(Color.parseColor("#FFE2E8F0"))
      gravity = Gravity.CENTER
      setPadding(0, 0, 0, dp(context, 12))
    }

    val statusBanner = TextView(context).apply {
      text = ""
      visibility = View.GONE
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
      setTextColor(Color.parseColor("#FF34D399"))
      gravity = Gravity.CENTER
      typeface = Typeface.DEFAULT_BOLD
      background = roundedBg("#FF0F292A", "#FF10B981", 1, 10f, context)
      setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
      layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
      ).apply {
        bottomMargin = dp(context, 10)
      }
    }

    // Inline PIN Container (hidden until user taps "РАЗБЛОКИРОВАТЬ ЭКРАН")
    val pinContainer = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      visibility = View.GONE
      gravity = Gravity.CENTER_HORIZONTAL
      background = roundedBg("#FF0B1324", "#FF334155", 1, 16f, context)
      setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
      layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
      ).apply {
        bottomMargin = dp(context, 12)
      }
    }

    val pinHintView = TextView(context).apply {
      text = "Введите PIN-код родителя для разблокировки:"
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
      setTextColor(Color.parseColor("#FF94A3B8"))
      gravity = Gravity.CENTER
    }

    val pinDisplayView = TextView(context).apply {
      text = "— — — —"
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
      setTextColor(Color.WHITE)
      typeface = Typeface.DEFAULT_BOLD
      gravity = Gravity.CENTER
      background = roundedBg("#FF1E293B", "#FF38BDF8", 1, 10f, context)
      setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
      layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
      ).apply {
        topMargin = dp(context, 8)
        bottomMargin = dp(context, 10)
      }
    }

    fun updatePinDisplay(errorText: String? = null) {
      if (errorText != null) {
        pinDisplayView.text = errorText
        pinDisplayView.setTextColor(Color.parseColor("#FFF43F5E"))
      } else {
        pinDisplayView.setTextColor(Color.WHITE)
        pinDisplayView.text = if (currentPin.isEmpty()) {
          "— — — —"
        } else {
          currentPin.map { "●" }.joinToString("  ")
        }
      }
    }

    fun submitCurrentPin() {
      if (currentPin.isEmpty()) {
        updatePinDisplay("Введите PIN")
        return
      }
      val pinToVerify = currentPin
      SosEmergencySender.verifyPinAndUnlockDevice(context, pinToVerify) { unlocked ->
        if (unlocked) {
          onUnlocked()
        } else {
          currentPin = ""
          updatePinDisplay("❌ Неверный PIN")
        }
      }
    }

    fun onDigitPressed(digit: String) {
      if (currentPin.length < 8) {
        currentPin += digit
        updatePinDisplay()
        // Auto-check immediately if 4+ digits match the Parent PIN
        if (currentPin.length >= 4) {
          val candidate = currentPin
          SosEmergencySender.verifyPinAndUnlockDevice(context, candidate) { unlocked ->
            if (unlocked) {
              onUnlocked()
            }
          }
        }
      }
    }

    pinContainer.addView(pinHintView)
    pinContainer.addView(pinDisplayView)

    val rows = listOf(
      listOf("1", "2", "3"),
      listOf("4", "5", "6"),
      listOf("7", "8", "9"),
      listOf("⌫", "0", "ОК")
    )

    for (rowItems in rows) {
      val rowLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.MATCH_PARENT,
          LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
          bottomMargin = dp(context, 6)
        }
      }
      for (keyLabel in rowItems) {
        val keyBtn = Button(context).apply {
          text = keyLabel
          isAllCaps = false
          setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
          typeface = Typeface.DEFAULT_BOLD
          setTextColor(Color.WHITE)
          background = when (keyLabel) {
            "ОК" -> roundedBg("#FF0284C7", null, 0, 10f, context)
            "⌫" -> roundedBg("#FF334155", null, 0, 10f, context)
            else -> roundedBg("#FF1E293B", "#FF334155", 1, 10f, context)
          }
          layoutParams = LinearLayout.LayoutParams(
            0,
            dp(context, 42),
            1f
          ).apply {
            marginStart = dp(context, 4)
            marginEnd = dp(context, 4)
          }
          setOnClickListener {
            when (keyLabel) {
              "⌫" -> {
                if (currentPin.isNotEmpty()) {
                  currentPin = currentPin.dropLast(1)
                }
                updatePinDisplay()
              }
              "ОК" -> submitCurrentPin()
              else -> onDigitPressed(keyLabel)
            }
          }
        }
        rowLayout.addView(keyBtn)
      }
      pinContainer.addView(rowLayout)
    }

    val unlockScreenButton = Button(context).apply {
      text = "🔓 РАЗБЛОКИРОВАТЬ ЭКРАН (PIN)"
      isAllCaps = false
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
      typeface = Typeface.DEFAULT_BOLD
      setTextColor(Color.parseColor("#FF020617"))
      background = roundedBg("#FF38BDF8", null, 0, 12f, context)
      layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        dp(context, 46)
      ).apply {
        bottomMargin = dp(context, 10)
      }
      setOnClickListener {
        if (pinContainer.visibility == View.VISIBLE) {
          pinContainer.visibility = View.GONE
          text = "🔓 РАЗБЛОКИРОВАТЬ ЭКРАН (PIN)"
        } else {
          currentPin = ""
          updatePinDisplay()
          pinContainer.visibility = View.VISIBLE
          text = "Скрыть ввод PIN-кода ▲"
        }
      }
    }

    val sosButton = Button(context).apply {
      text = "🚨 ЭКСТРЕННАЯ СВЯЗЬ SOS"
      isAllCaps = false
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
      typeface = Typeface.DEFAULT_BOLD
      setTextColor(Color.WHITE)
      background = roundedBg("#FFE11D48", null, 0, 12f, context)
      layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        dp(context, 46)
      )
      setOnClickListener {
        statusBanner.visibility = View.VISIBLE
        SosEmergencySender.sendSosToParent(
          context = context,
          sourceNote = if (isDeviceTotalLock) "Заблокированный экран" else "Окно ограничения"
        ) { statusMsg ->
          statusBanner.visibility = View.VISIBLE
          statusBanner.text = statusMsg
        }
      }
    }

    cardLayout.addView(iconText)
    cardLayout.addView(titleView)
    cardLayout.addView(descView)
    cardLayout.addView(statusBanner)
    cardLayout.addView(unlockScreenButton)
    cardLayout.addView(pinContainer)

    if (!isDeviceTotalLock && onCloseToHome != null) {
      val homeButton = Button(context).apply {
        text = "НА ГЛАВНЫЙ ЭКРАН (ЗАКРЫТЬ)"
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        background = roundedBg("#FF334155", null, 0, 12f, context)
        layoutParams = LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.MATCH_PARENT,
          dp(context, 44)
        ).apply {
          bottomMargin = dp(context, 10)
        }
        setOnClickListener {
          onCloseToHome.invoke()
        }
      }
      cardLayout.addView(homeButton)
    }

    cardLayout.addView(sosButton)
    rootLayout.addView(cardLayout)
    return rootLayout
  }
}
