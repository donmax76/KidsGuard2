package com.example.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.example.MainActivity
import com.example.R
import com.example.data.cloud.CloudSyncManager
import com.example.data.local.KidsGuardDatabase
import com.example.data.model.AlertNotificationEntity
import com.example.data.model.ChatMessageEntity
import com.example.util.SoundAlertManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private const val TAG = "FloatingChatOverlay"
private const val CHAT_CHANNEL_ID = "kidsguard_floating_chat_sos_channel"
private const val KEY_TEXT_REPLY = "key_floating_chat_inline_reply"

/**
 * Phase 4C: Floating Chat & SOS Messenger Overlay (SYSTEM_ALERT_WINDOW) + High-Priority Heads-Up Notification.
 * Works for BOTH Parent and Child roles:
 * - Renders a draggable floating chat bubble (Messenger Chat Head) and an expandable interactive chat window
 *   over any foreground application when SYSTEM_ALERT_WINDOW is granted.
 * - Allows immediate reading of messages/SOS coordinates, quick-reply chips, and full keyboard text replies
 *   without opening the full KidsGuard activity.
 * - Simultaneously posts a high-priority heads-up notification with Direct Inline Reply (RemoteInput) as a
 *   guaranteed system-level popup on devices where overlay permission is not yet enabled.
 */
class FloatingChatOverlayService : Service() {

  companion object {
    private const val EXTRA_CHILD_ID = "extra_child_id"
    private const val EXTRA_SENDER_NAME = "extra_sender_name"
    private const val EXTRA_SENDER_ROLE = "extra_sender_role"
    private const val EXTRA_MESSAGE_TEXT = "extra_message_text"
    private const val EXTRA_IS_SOS = "extra_is_sos"
    private const val EXTRA_AUTO_EXPAND = "extra_auto_expand"
    private const val ACTION_SHOW_POPUP = "com.example.action.SHOW_CHAT_POPUP"
    private const val ACTION_DISMISS_POPUP = "com.example.action.DISMISS_CHAT_POPUP"

    @Volatile
    private var lastShownSignature: String = ""

    @Volatile
    private var lastShownTimestampMs: Long = 0L

    /**
     * Entry point called whenever a new remote Chat message or SOS alert is received in the background or foreground.
     */
    fun showIncomingMessageOrSos(
      context: Context,
      childId: Long,
      senderName: String,
      senderRole: String,
      messageText: String,
      isSosAlert: Boolean = false,
      autoExpandWindow: Boolean = true
    ) {
      val appContext = context.applicationContext
      val now = System.currentTimeMillis()
      val sig = "$childId|$senderRole|${messageText.trim()}|$isSosAlert"
      if (sig == lastShownSignature && abs(now - lastShownTimestampMs) < 2000L) {
        return
      }
      lastShownSignature = sig
      lastShownTimestampMs = now

      // 1. Always post a high-priority Heads-Up Notification with Inline Reply (RemoteInput)
      postHighPriorityChatNotification(
        context = appContext,
        childId = childId,
        senderName = senderName,
        senderRole = senderRole,
        messageText = messageText,
        isSosAlert = isSosAlert
      )

      // 2. If SYSTEM_ALERT_WINDOW permission is granted, launch the floating bubble / interactive popup window
      val canDraw = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(appContext)
      if (canDraw) {
        try {
          val intent = Intent(appContext, FloatingChatOverlayService::class.java).apply {
            action = ACTION_SHOW_POPUP
            putExtra(EXTRA_CHILD_ID, childId)
            putExtra(EXTRA_SENDER_NAME, senderName)
            putExtra(EXTRA_SENDER_ROLE, senderRole)
            putExtra(EXTRA_MESSAGE_TEXT, messageText)
            putExtra(EXTRA_IS_SOS, isSosAlert)
            putExtra(EXTRA_AUTO_EXPAND, autoExpandWindow || isSosAlert)
          }
          appContext.startService(intent)
        } catch (e: Exception) {
          Log.w(TAG, "Unable to start FloatingChatOverlayService: ${e.message}")
        }
      }
    }

    fun dismissOverlay(context: Context) {
      try {
        val intent = Intent(context.applicationContext, FloatingChatOverlayService::class.java).apply {
          action = ACTION_DISMISS_POPUP
        }
        context.applicationContext.startService(intent)
      } catch (_: Exception) {}
    }

    fun ensureNotificationChannel(context: Context) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val existing = nm.getNotificationChannel(CHAT_CHANNEL_ID)
        if (existing == null) {
          val channel = NotificationChannel(
            CHAT_CHANNEL_ID,
            "KidsGuard • Всплывающий чат и SOS",
            NotificationManager.IMPORTANCE_HIGH
          ).apply {
            description = "Мгновенные всплывающие сообщения семейного чата и экстренные сигналы SOS"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 250, 120, 250)
          }
          nm.createNotificationChannel(channel)
        }
      }
    }

    fun postHighPriorityChatNotification(
      context: Context,
      childId: Long,
      senderName: String,
      senderRole: String,
      messageText: String,
      isSosAlert: Boolean
    ) {
      try {
        ensureNotificationChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
          flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
          putExtra("OPEN_CHAT_CHILD_ID", childId)
        }
        val openPendingIntent = PendingIntent.getActivity(
          context,
          (childId.toInt() + 7100),
          openAppIntent,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Direct Inline Reply action in the notification shade / heads-up banner
        val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
          .setLabel("Написать быстрый ответ...")
          .build()

        val replyIntent = Intent(context, ChatInlineReplyReceiver::class.java).apply {
          putExtra(EXTRA_CHILD_ID, childId)
          putExtra(EXTRA_SENDER_NAME, senderName)
          putExtra(EXTRA_SENDER_ROLE, senderRole)
        }
        val replyFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
          PendingIntent.FLAG_UPDATE_CURRENT
        }
        val replyPendingIntent = PendingIntent.getBroadcast(
          context,
          (childId.toInt() + 8100),
          replyIntent,
          replyFlags
        )

        val replyAction = NotificationCompat.Action.Builder(
          android.R.drawable.ic_menu_send,
          "Ответить",
          replyPendingIntent
        )
          .addRemoteInput(remoteInput)
          .setAllowGeneratedReplies(true)
          .build()

        val title = if (isSosAlert) {
          "🚨 ЭКСТРЕННЫЙ SOS: $senderName"
        } else {
          "💬 $senderName • Семейный чат"
        }

        val notification = NotificationCompat.Builder(context, CHAT_CHANNEL_ID)
          .setSmallIcon(R.mipmap.ic_launcher)
          .setContentTitle(title)
          .setContentText(messageText)
          .setStyle(NotificationCompat.BigTextStyle().bigText(messageText))
          .setPriority(NotificationCompat.PRIORITY_HIGH)
          .setCategory(if (isSosAlert) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_MESSAGE)
          .setDefaults(NotificationCompat.DEFAULT_ALL)
          .setAutoCancel(true)
          .setContentIntent(openPendingIntent)
          .addAction(replyAction)
          .build()

        nm.notify((childId.toInt() + if (isSosAlert) 9500 else 9200), notification)
      } catch (e: Exception) {
        Log.w(TAG, "postHighPriorityChatNotification error: ${e.message}")
      }
    }
  }

  private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
  private var windowManager: WindowManager? = null
  private var rootOverlayContainer: FrameLayout? = null
  private var overlayParams: WindowManager.LayoutParams? = null

  private var bubbleView: View? = null
  private var badgeTextView: TextView? = null
  private var expandedCardView: View? = null
  private var headerTitleView: TextView? = null
  private var headerSubtitleView: TextView? = null
  private var messagesContainer: LinearLayout? = null
  private var messagesScrollView: ScrollView? = null
  private var replyInput: EditText? = null

  private var activeChildId: Long = 1L
  private var currentSenderName: String = "Семейный чат"
  private var currentDeviceRole: String = "PARENT"
  private var unreadCount: Int = 0
  private var isSosMode: Boolean = false
  private var isExpanded: Boolean = true
  private var messagesCollectJob: Job? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val action = intent?.action
    if (action == ACTION_DISMISS_POPUP) {
      removeOverlayViews()
      stopSelf()
      return START_NOT_STICKY
    }

    val canDraw = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
    if (!canDraw) {
      stopSelf()
      return START_NOT_STICKY
    }

    val incomingChildId = intent?.getLongExtra(EXTRA_CHILD_ID, 1L)?.takeIf { it > 0L } ?: activeChildId
    val incomingSender = intent?.getStringExtra(EXTRA_SENDER_NAME)?.ifBlank { "Семейный чат" } ?: currentSenderName
    val incomingText = intent?.getStringExtra(EXTRA_MESSAGE_TEXT).orEmpty()
    val incomingSos = intent?.getBooleanExtra(EXTRA_IS_SOS, false) ?: false
    val autoExpand = intent?.getBooleanExtra(EXTRA_AUTO_EXPAND, true) ?: true

    activeChildId = incomingChildId
    currentSenderName = incomingSender
    isSosMode = incomingSos
    unreadCount = (unreadCount + 1).coerceAtMost(99)

    vibrateAndChime(incomingSos)

    serviceScope.launch(Dispatchers.IO) {
      val dao = KidsGuardDatabase.getDatabase(applicationContext).kidsGuardDao()
      val cfg = dao.getFamilyConfigOnce()
      currentDeviceRole = if (cfg?.selectedRole == "CHILD") "CHILD" else "PARENT"
      val childProfile = dao.getChildByIdOnce(activeChildId)
      if (currentDeviceRole == "PARENT" && childProfile != null && childProfile.name.isNotBlank()) {
        currentSenderName = childProfile.name
      }

      launch(Dispatchers.Main) {
        ensureOverlayCreated()
        updateOverlayHeaderAndState(
          latestPreview = incomingText,
          expandNow = autoExpand
        )
        observeConversationForChild(activeChildId)
      }
    }

    return START_NOT_STICKY
  }

  private fun vibrateAndChime(isSos: Boolean) {
    try {
      val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
      if (vibrator != null && vibrator.hasVibrator()) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          val timings = if (isSos) longArrayOf(0, 350, 120, 350, 120, 450) else longArrayOf(0, 180, 90, 180)
          vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
        } else {
          @Suppress("DEPRECATION")
          vibrator.vibrate(if (isSos) 600L else 250L)
        }
      }
      if (isSos) {
        SoundAlertManager.playLoudAttentionSignal(applicationContext)
      }
    } catch (_: Exception) {}
  }

  private fun dp(value: Int): Int =
    TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP,
      value.toFloat(),
      resources.displayMetrics
    ).toInt()

  @SuppressLint("ClickableViewAccessibility")
  private fun ensureOverlayCreated() {
    if (rootOverlayContainer != null) return
    val wm = windowManager ?: return

    val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
      @Suppress("DEPRECATION")
      WindowManager.LayoutParams.TYPE_PHONE
    }

    // Start focusable when expanded so EditText can receive keyboard input immediately
    val initialFlags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
      WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
      WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

    val params = WindowManager.LayoutParams(
      WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.WRAP_CONTENT,
      layoutType,
      initialFlags,
      PixelFormat.TRANSLUCENT
    ).apply {
      gravity = Gravity.TOP or Gravity.START
      x = dp(16)
      y = dp(76)
      softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
    }
    overlayParams = params

    val root = FrameLayout(this)

    // 1. Collapsed Draggable Messenger Bubble View
    val bubbleContainer = FrameLayout(this).apply {
      layoutParams = FrameLayout.LayoutParams(dp(64), dp(64))
      background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        colors = intArrayOf(Color.parseColor("#0284C7"), Color.parseColor("#2563EB"))
        setStroke(dp(2), Color.parseColor("#38BDF8"))
      }
      elevation = dp(10).toFloat()
    }

    val bubbleIcon = TextView(this).apply {
      text = "💬"
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
      gravity = Gravity.CENTER
      layoutParams = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT
      )
    }
    bubbleContainer.addView(bubbleIcon)

    val badge = TextView(this).apply {
      text = "1"
      setTextColor(Color.WHITE)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
      typeface = Typeface.DEFAULT_BOLD
      gravity = Gravity.CENTER
      setPadding(dp(5), dp(1), dp(5), dp(1))
      background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor("#EF4444"))
        setStroke(dp(1), Color.WHITE)
      }
      layoutParams = FrameLayout.LayoutParams(dp(22), dp(22)).apply {
        gravity = Gravity.TOP or Gravity.END
      }
    }
    badgeTextView = badge
    bubbleContainer.addView(badge)

    // Allow dragging the bubble around the screen or tapping to expand
    var initialX = 0
    var initialY = 0
    var initialTouchX = 0f
    var initialTouchY = 0f
    bubbleContainer.setOnTouchListener { _, event ->
      val lp = overlayParams ?: return@setOnTouchListener false
      when (event.action) {
        MotionEvent.ACTION_DOWN -> {
          initialX = lp.x
          initialY = lp.y
          initialTouchX = event.rawX
          initialTouchY = event.rawY
          true
        }
        MotionEvent.ACTION_MOVE -> {
          lp.x = initialX + (event.rawX - initialTouchX).toInt()
          lp.y = initialY + (event.rawY - initialTouchY).toInt()
          try { wm.updateViewLayout(root, lp) } catch (_: Exception) {}
          true
        }
        MotionEvent.ACTION_UP -> {
          val dx = abs(event.rawX - initialTouchX)
          val dy = abs(event.rawY - initialTouchY)
          if (dx < dp(10) && dy < dp(10)) {
            setExpandedState(true)
          }
          true
        }
        else -> false
      }
    }
    bubbleView = bubbleContainer
    root.addView(bubbleContainer)

    // 2. Expanded Interactive Floating Chat Card
    val cardWidth = (resources.displayMetrics.widthPixels * 0.90f).toInt().coerceAtMost(dp(360))
    val card = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      layoutParams = FrameLayout.LayoutParams(cardWidth, FrameLayout.LayoutParams.WRAP_CONTENT)
      background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(18).toFloat()
        setColor(Color.parseColor("#0F172A"))
        setStroke(dp(2), Color.parseColor("#0EA5E9"))
      }
      elevation = dp(14).toFloat()
    }

    // Header bar (Draggable + Minimize + Open App + Close)
    val header = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(dp(14), dp(10), dp(10), dp(10))
      background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadii = floatArrayOf(
          dp(16).toFloat(), dp(16).toFloat(),
          dp(16).toFloat(), dp(16).toFloat(),
          0f, 0f, 0f, 0f
        )
        setColor(Color.parseColor("#1E293B"))
      }
    }

    val titleCol = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    val titleTv = TextView(this).apply {
      text = "💬 Семейный чат"
      setTextColor(Color.WHITE)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
      typeface = Typeface.DEFAULT_BOLD
      maxLines = 1
    }
    headerTitleView = titleTv

    val subtitleTv = TextView(this).apply {
      text = "Мгновенный ответ поверх экрана"
      setTextColor(Color.parseColor("#38BDF8"))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
      maxLines = 1
    }
    headerSubtitleView = subtitleTv

    titleCol.addView(titleTv)
    titleCol.addView(subtitleTv)
    header.addView(titleCol)

    // Minimize to floating bubble button
    val minBtn = createHeaderActionButton("—", "#334155") {
      setExpandedState(false)
    }
    // Open full app button
    val openAppBtn = createHeaderActionButton("↗", "#0284C7") {
      try {
        val openIntent = Intent(applicationContext, MainActivity::class.java).apply {
          flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
          putExtra("OPEN_CHAT_CHILD_ID", activeChildId)
        }
        startActivity(openIntent)
      } catch (_: Exception) {}
      setExpandedState(false)
    }
    // Close overlay button
    val closeBtn = createHeaderActionButton("✕", "#DC2626") {
      removeOverlayViews()
      stopSelf()
    }

    header.addView(minBtn)
    header.addView(openAppBtn)
    header.addView(closeBtn)

    // Allow dragging the expanded card by its header
    header.setOnTouchListener { _, event ->
      val lp = overlayParams ?: return@setOnTouchListener false
      when (event.action) {
        MotionEvent.ACTION_DOWN -> {
          initialX = lp.x
          initialY = lp.y
          initialTouchX = event.rawX
          initialTouchY = event.rawY
          true
        }
        MotionEvent.ACTION_MOVE -> {
          lp.x = initialX + (event.rawX - initialTouchX).toInt()
          lp.y = initialY + (event.rawY - initialTouchY).toInt()
          try { wm.updateViewLayout(root, lp) } catch (_: Exception) {}
          true
        }
        else -> false
      }
    }
    card.addView(header)

    // Scrollable message history container
    val scroll = ScrollView(this).apply {
      layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        dp(190)
      )
      setPadding(dp(12), dp(8), dp(12), dp(8))
    }
    val msgList = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      layoutParams = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.WRAP_CONTENT
      )
    }
    scroll.addView(msgList)
    messagesScrollView = scroll
    messagesContainer = msgList
    card.addView(scroll)

    // Quick-Reply Chips Row (1-tap response without typing)
    val quickRow = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      setPadding(dp(10), dp(4), dp(10), dp(4))
    }
    val quickReplies = listOf("Ок 👍", "Скоро буду 🏠", "Позвони 📞", "Я на связи ❤️")
    for (qr in quickReplies) {
      val chip = TextView(this).apply {
        text = qr
        setTextColor(Color.parseColor("#E0F2FE"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setPadding(dp(8), dp(5), dp(8), dp(5))
        background = GradientDrawable().apply {
          cornerRadius = dp(12).toFloat()
          setColor(Color.parseColor("#1E293B"))
          setStroke(dp(1), Color.parseColor("#0284C7"))
        }
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
          marginEnd = dp(4)
        }
        layoutParams = lp
        gravity = Gravity.CENTER
        maxLines = 1
        setOnClickListener {
          sendQuickOrTypedReply(qr)
        }
      }
      quickRow.addView(chip)
    }
    card.addView(quickRow)

    // Bottom Input Bar (EditText + Send button)
    val inputRow = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(dp(10), dp(8), dp(10), dp(12))
    }

    val edit = EditText(this).apply {
      hint = "Написать сообщение..."
      setHintTextColor(Color.parseColor("#64748B"))
      setTextColor(Color.WHITE)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
      imeOptions = EditorInfo.IME_ACTION_SEND
      maxLines = 2
      setPadding(dp(12), dp(8), dp(12), dp(8))
      background = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setColor(Color.parseColor("#1E293B"))
        setStroke(dp(1), Color.parseColor("#334155"))
      }
      layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
        marginEnd = dp(8)
      }
      setOnClickListener {
        enableKeyboardFocus(true)
      }
      setOnEditorActionListener { _, actionId, _ ->
        if (actionId == EditorInfo.IME_ACTION_SEND) {
          val txt = text?.toString()?.trim().orEmpty()
          if (txt.isNotEmpty()) {
            sendQuickOrTypedReply(txt)
            setText("")
          }
          true
        } else false
      }
    }
    replyInput = edit

    val sendBtn = TextView(this).apply {
      text = "Отпр."
      setTextColor(Color.WHITE)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
      typeface = Typeface.DEFAULT_BOLD
      gravity = Gravity.CENTER
      setPadding(dp(14), dp(10), dp(14), dp(10))
      background = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setColor(Color.parseColor("#0284C7"))
      }
      setOnClickListener {
        val txt = edit.text?.toString()?.trim().orEmpty()
        if (txt.isNotEmpty()) {
          sendQuickOrTypedReply(txt)
          edit.setText("")
        }
      }
    }

    inputRow.addView(edit)
    inputRow.addView(sendBtn)
    card.addView(inputRow)

    expandedCardView = card
    root.addView(card)

    rootOverlayContainer = root
    try {
      wm.addView(root, params)
    } catch (e: Exception) {
      Log.e(TAG, "Failed adding floating chat overlay: ${e.message}")
    }
  }

  private fun createHeaderActionButton(label: String, bgHex: String, onClick: () -> Unit): TextView {
    return TextView(this).apply {
      text = label
      setTextColor(Color.WHITE)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
      typeface = Typeface.DEFAULT_BOLD
      gravity = Gravity.CENTER
      val size = dp(28)
      layoutParams = LinearLayout.LayoutParams(size, size).apply {
        marginStart = dp(6)
      }
      background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor(bgHex))
      }
      setOnClickListener { onClick() }
    }
  }

  private fun enableKeyboardFocus(focusable: Boolean) {
    val wm = windowManager ?: return
    val root = rootOverlayContainer ?: return
    val lp = overlayParams ?: return
    lp.flags = if (focusable) {
      WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
    } else {
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
    }
    try {
      wm.updateViewLayout(root, lp)
      if (focusable) {
        replyInput?.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(replyInput, InputMethodManager.SHOW_IMPLICIT)
      }
    } catch (_: Exception) {}
  }

  private fun setExpandedState(expanded: Boolean) {
    isExpanded = expanded
    if (expanded) {
      unreadCount = 0
      bubbleView?.visibility = View.GONE
      expandedCardView?.visibility = View.VISIBLE
      enableKeyboardFocus(true)
      messagesScrollView?.post {
        messagesScrollView?.fullScroll(View.FOCUS_DOWN)
      }
    } else {
      expandedCardView?.visibility = View.GONE
      bubbleView?.visibility = View.VISIBLE
      badgeTextView?.text = unreadCount.toString()
      badgeTextView?.visibility = if (unreadCount > 0) View.VISIBLE else View.GONE
      enableKeyboardFocus(false)
    }
  }

  private fun updateOverlayHeaderAndState(latestPreview: String, expandNow: Boolean) {
    headerTitleView?.text = if (isSosMode) {
      "🚨 SOS ТРЕВОГА • $currentSenderName"
    } else {
      "💬 Чат • $currentSenderName"
    }
    headerSubtitleView?.text = if (isSosMode) {
      "Экстренный вызов с координатами!"
    } else if (latestPreview.isNotBlank()) {
      "Новое сообщение • Онлайн"
    } else {
      "Быстрый ответ поверх окон"
    }
    headerSubtitleView?.setTextColor(
      Color.parseColor(if (isSosMode) "#FCA5A5" else "#38BDF8")
    )

    badgeTextView?.text = unreadCount.toString()
    badgeTextView?.visibility = if (unreadCount > 0) View.VISIBLE else View.GONE

    setExpandedState(expandNow)
  }

  private fun observeConversationForChild(childId: Long) {
    messagesCollectJob?.cancel()
    messagesCollectJob = serviceScope.launch(Dispatchers.IO) {
      val dao = KidsGuardDatabase.getDatabase(applicationContext).kidsGuardDao()
      dao.getChatMessagesForChild(childId).collectLatest { messages ->
        val recent = messages.takeLast(15)
        launch(Dispatchers.Main) {
          renderMessagesList(recent)
        }
      }
    }
  }

  private fun renderMessagesList(messages: List<ChatMessageEntity>) {
    val container = messagesContainer ?: return
    container.removeAllViews()
    val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    if (messages.isEmpty()) {
      val emptyTv = TextView(this).apply {
        text = "Напишите сообщение для мгновенной отправки..."
        setTextColor(Color.parseColor("#94A3B8"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(dp(8), dp(12), dp(8), dp(12))
      }
      container.addView(emptyTv)
      return
    }

    for (msg in messages) {
      val isMine = msg.senderRole.equals(currentDeviceRole, ignoreCase = true)
      val isSosMsg = msg.isAttentionSignal || msg.text.contains("SOS", ignoreCase = true)

      val row = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = if (isMine) Gravity.END else Gravity.START
        layoutParams = LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.MATCH_PARENT,
          LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
          bottomMargin = dp(6)
        }
      }

      val bubble = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(7), dp(10), dp(7))
        background = GradientDrawable().apply {
          cornerRadius = dp(12).toFloat()
          when {
            isSosMsg -> {
              setColor(Color.parseColor("#7F1D1D"))
              setStroke(dp(1), Color.parseColor("#EF4444"))
            }
            isMine -> setColor(Color.parseColor("#0284C7"))
            else -> setColor(Color.parseColor("#1E293B"))
          }
        }
      }

      val senderTv = TextView(this).apply {
        text = "${msg.senderName} • ${timeFmt.format(Date(msg.timestamp))}"
        setTextColor(Color.parseColor("#94A3B8"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
      }

      val bodyTv = TextView(this).apply {
        text = msg.text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
      }

      bubble.addView(senderTv)
      bubble.addView(bodyTv)
      row.addView(bubble)
      container.addView(row)
    }

    messagesScrollView?.post {
      messagesScrollView?.fullScroll(View.FOCUS_DOWN)
    }
  }

  private fun sendQuickOrTypedReply(replyText: String) {
    val clean = replyText.trim()
    if (clean.isEmpty()) return
    val targetChildId = activeChildId

    serviceScope.launch(Dispatchers.IO) {
      try {
        val dao = KidsGuardDatabase.getDatabase(applicationContext).kidsGuardDao()
        val sync = CloudSyncManager(applicationContext)
        val cfg = dao.getFamilyConfigOnce()
        val role = if (cfg?.selectedRole == "CHILD") "CHILD" else "PARENT"
        val child = dao.getChildByIdOnce(targetChildId)
        val senderName = if (role == "CHILD") {
          child?.name?.ifBlank { "Ребёнок" } ?: "Ребёнок"
        } else {
          "Родитель"
        }

        val msg = ChatMessageEntity(
          childId = targetChildId,
          senderRole = role,
          senderName = senderName,
          text = clean,
          timestamp = System.currentTimeMillis(),
          isRead = false,
          isAttentionSignal = false
        )
        val newId = dao.insertChatMessage(msg)
        sync.sendChatMessage(msg.copy(id = newId))

        if (role == "CHILD") {
          val alert = AlertNotificationEntity(
            childId = targetChildId,
            type = "CHAT_MESSAGE",
            title = "💬 Сообщение от «$senderName»",
            message = clean,
            timestamp = msg.timestamp
          )
          dao.insertAlert(alert)
          sync.sendAlert(alert)
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error sending reply from overlay: ${e.message}")
      }
    }
  }

  private fun removeOverlayViews() {
    messagesCollectJob?.cancel()
    val wm = windowManager
    val root = rootOverlayContainer
    if (wm != null && root != null) {
      try {
        wm.removeView(root)
      } catch (_: Exception) {}
    }
    rootOverlayContainer = null
    bubbleView = null
    expandedCardView = null
  }

  override fun onDestroy() {
    removeOverlayViews()
    super.onDestroy()
  }
}

/**
 * Handles inline replies sent directly from the High-Priority Heads-Up Notification banner.
 */
class ChatInlineReplyReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val results = RemoteInput.getResultsFromIntent(intent) ?: return
    val replyText = results.getCharSequence(KEY_TEXT_REPLY)?.toString()?.trim().orEmpty()
    if (replyText.isEmpty()) return

    val childId = intent.getLongExtra("extra_child_id", 1L).takeIf { it > 0L } ?: 1L
    val pendingResult = goAsync()
    val appContext = context.applicationContext

    CoroutineScope(Dispatchers.IO).launch {
      try {
        val dao = KidsGuardDatabase.getDatabase(appContext).kidsGuardDao()
        val sync = CloudSyncManager(appContext)
        val cfg = dao.getFamilyConfigOnce()
        val role = if (cfg?.selectedRole == "CHILD") "CHILD" else "PARENT"
        val child = dao.getChildByIdOnce(childId)
        val senderName = if (role == "CHILD") {
          child?.name?.ifBlank { "Ребёнок" } ?: "Ребёнок"
        } else {
          "Родитель"
        }

        val msg = ChatMessageEntity(
          childId = childId,
          senderRole = role,
          senderName = senderName,
          text = replyText,
          timestamp = System.currentTimeMillis(),
          isRead = false,
          isAttentionSignal = false
        )
        val id = dao.insertChatMessage(msg)
        sync.sendChatMessage(msg.copy(id = id))

        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.cancel(childId.toInt() + 9200)
        nm?.cancel(childId.toInt() + 9500)
      } catch (e: Exception) {
        Log.e(TAG, "ChatInlineReplyReceiver error: ${e.message}")
      } finally {
        pendingResult.finish()
      }
    }
  }
}
