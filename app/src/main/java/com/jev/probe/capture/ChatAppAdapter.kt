package com.jev.probe.capture

import android.content.res.Resources
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg

/**
 * Per-app capture rules. An adapter turns one messaging app's open chat window
 * into a neutral [ChatSnapshot]; everything downstream (Jev judgment, overlay,
 * fill) is app-agnostic. [extract] returns null when the current window is not
 * that app's chat (e.g. its home/list screen), so the service shows nothing.
 *
 * The disguised accessibility service (registered as SelectToSpeakService) lets
 * us read the node tree of apps that obfuscate it for normal services (WeChat).
 * Feishu/Lark does not obfuscate, so its adapter reads plain resource-ids.
 */
interface ChatAppAdapter {
    val pkg: String
    fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot?
}

/** Shared helpers. */
private fun looksLikeTimestamp(t: String): Boolean =
    Regex("""\d{1,2}[:：]\d{2}""").containsMatchIn(t) ||
        Regex("""\d+月\d+日""").containsMatchIn(t) ||
        t == "昨天" || t == "今天"

/**
 * Conversation title in the top action bar: the topmost short, roughly centered
 * text above the first message bubble. Constrained so we never grab an in-chat
 * timestamp. Used by WeChat, and by QQ as a fallback when its title id is absent.
 */
private fun findTitleInActionBar(
    root: AccessibilityNodeInfo,
    firstBubbleTop: Int,
    width: Int,
    res: Resources,
    minCenterRatio: Double = 0.25,
    maxCenterRatio: Double = 0.75
): String? {
    val actionBarMax = minOf(firstBubbleTop, (res.displayMetrics.heightPixels * 0.14).toInt())
    val minCenterX = (width * minCenterRatio).toInt()
    val maxCenterX = (width * maxCenterRatio).toInt()
    val stack = ArrayDeque<AccessibilityNodeInfo>()
    stack.addLast(root)
    var best: String? = null
    var bestTop = Int.MAX_VALUE
    var guard = 0
    while (stack.isNotEmpty() && guard < 5000) {
        guard++
        val node = stack.removeLast()
        val text = node.text?.toString()
        if (!text.isNullOrBlank() && text.length <= 24 && !looksLikeTimestamp(text)) {
            val b = Rect(); node.getBoundsInScreen(b)
            if (b.bottom in 1 until actionBarMax && b.centerX() in minCenterX..maxCenterX) {
                if (b.top < bestTop) { bestTop = b.top; best = text }
            }
        }
        for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
    }
    return best
}

/** WeChat (com.tencent.mm). Message bubbles carry a stable id; sender side is
 *  the bubble's horizontal position (right = me, left = other). */
class WeChatAdapter : ChatAppAdapter {
    override val pkg = "com.tencent.mm"

    override fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot? {
        val width = res.displayMetrics.widthPixels
        val height = res.displayMetrics.heightPixels

        // First try the legacy verified node id. This keeps compatibility with
        // older WeChat builds where message text lived under id/bkl.
        val legacy = extractLegacy(root, res, width)
        if (legacy != null) return legacy

        // Newer WeChat builds may rotate obfuscated resource ids. Instead of
        // pinning another short-lived id, detect a chat by the editable input
        // and collect visible text nodes from the message viewport.
        return extractByGeometry(root, res, width, height)
    }

    private fun extractLegacy(
        root: AccessibilityNodeInfo,
        res: Resources,
        width: Int
    ): ChatSnapshot? {
        val bubbles = ArrayList<Triple<Int, Int, String>>()
        var firstBubbleTop = Int.MAX_VALUE

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 6000) {
            guard++
            val node = stack.removeLast()
            val text = node.text?.toString()
            if (node.viewIdResourceName == LEGACY_BUBBLE_ID && !text.isNullOrBlank()) {
                val b = Rect()
                node.getBoundsInScreen(b)
                bubbles.add(Triple(b.top, b.centerX(), text.trim()))
                if (b.top < firstBubbleTop) firstBubbleTop = b.top
            }
            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        if (bubbles.isEmpty()) return null

        val title = findTitleInActionBar(root, firstBubbleTop, width, res)
        bubbles.sortBy { it.first }
        val msgs = bubbles.map { (_, cx, text) ->
            Msg(if (cx > width / 2) "me" else "other", text)
        }
        return ChatSnapshot(title, msgs)
    }

    private fun extractByGeometry(
        root: AccessibilityNodeInfo,
        res: Resources,
        width: Int,
        height: Int
    ): ChatSnapshot? {
        var inputTop = height
        var hasEditable = false

        // top, left, right, text
        val candidates = ArrayList<FallbackBubble>()
        val seen = HashSet<String>()

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 8000) {
            guard++
            val node = stack.removeLast()
            val cls = node.className?.toString() ?: ""
            val b = Rect()
            node.getBoundsInScreen(b)

            if (node.isEditable || cls == "android.widget.EditText") {
                hasEditable = true
                if (b.top in 1 until inputTop) inputTop = b.top
            }

            val text = node.text?.toString()?.trim()
            if (!text.isNullOrBlank() &&
                !node.isEditable &&
                text.length <= 500 &&
                !looksLikeTimestamp(text)
            ) {
                val key = "${b.left},${b.top},${b.right},${b.bottom}:$text"
                if (seen.add(key)) {
                    candidates.add(FallbackBubble(b.top, b.bottom, b.left, b.right, text, cls))
                }
            }

            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }

        // A real WeChat conversation has a message composer. Without one this
        // is probably the chat list, contacts, moments, settings, etc.
        if (!hasEditable) return null

        val topBand = (height * 0.12f).toInt()
        val bottomLimit = if (inputTop < height) inputTop - (height * 0.01f).toInt()
        else (height * 0.82f).toInt()

        val messageArea = candidates.filter { c ->
            c.top > topBand &&
            c.bottom < bottomLimit &&
            c.right > c.left &&
            c.bottom > c.top &&
            !isWeChatChrome(c.text)
        }

        if (messageArea.isEmpty()) return null

        val firstTop = messageArea.minOf { it.top }
        val title = findTitleInActionBar(root, firstTop, width, res)

        // Remove obvious nested duplicates: if the same text appears at nearly
        // the same vertical position, keep the tighter text node.
        val deduped = ArrayList<FallbackBubble>()
        for (c in messageArea.sortedWith(compareBy<FallbackBubble> { it.top }.thenBy { it.left })) {
            val duplicateIndex = deduped.indexOfFirst { d ->
                d.text == c.text && kotlin.math.abs(d.top - c.top) <= 8
            }
            if (duplicateIndex < 0) {
                deduped.add(c)
            } else {
                val old = deduped[duplicateIndex]
                val oldArea = (old.right - old.left) * (old.bottom - old.top)
                val newArea = (c.right - c.left) * (c.bottom - c.top)
                if (newArea in 1 until oldArea) deduped[duplicateIndex] = c
            }
        }

        val msgs = deduped.map { c ->
            // WeChat incoming bubbles live on the left and our own on the right.
            // For long multi-line bubbles use which screen edge the text block is
            // closer to rather than centerX alone.
            val leftGap = c.left
            val rightGap = width - c.right
            val side = when {
                rightGap + (width * 0.08f).toInt() < leftGap -> "me"
                leftGap + (width * 0.08f).toInt() < rightGap -> "other"
                (c.left + c.right) / 2 > width / 2 -> "me"
                else -> "other"
            }
            Msg(side, c.text)
        }

        return if (msgs.isEmpty()) null else ChatSnapshot(title, msgs)
    }

    private fun isWeChatChrome(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        return t in setOf(
            "发送", "按住 说话", "切换到按住说话", "切换到键盘",
            "语音", "更多", "表情", "返回", "聊天信息"
        )
    }

    private data class FallbackBubble(
        val top: Int,
        val bottom: Int,
        val left: Int,
        val right: Int,
        val text: String,
        val cls: String
    )

    companion object {
        private const val LEGACY_BUBBLE_ID = "com.tencent.mm:id/bkl"
    }
}

/**
 * Mobile QQ (com.tencent.mobileqq). Nodes are NOT obfuscated (verified on QQ
 * 9.3.50 / Xiaomi 14, 1200x2670): message bodies are plain TextViews carrying
 * `id/mjn`, so collecting only that id already excludes timestamps, sender
 * nicknames (`id/mjq`) and the full-width system notice strips.
 *
 * The whole app lives under one SplashActivity (fragment architecture), so
 * "are we in a chat window" can only be answered by the tree itself — here, by
 * whether any `id/mjn` node exists. No bodies → null.
 *
 * Sender side: QQ pins the avatar to the outer edge of its own side (others on
 * the left at x≈156/1200 ≈ 13% of width, me on the right at width−156). A long
 * incoming message can push its center past mid-screen, so we compare which
 * edge of the bubble hugs its avatar column instead of using the center point.
 */
class QQAdapter : ChatAppAdapter {
    override val pkg = "com.tencent.mobileqq"

    override fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot? {
        val width = res.displayMetrics.widthPixels
        // top, left, right, text
        val bubbles = ArrayList<Bubble>()
        var firstBubbleTop = Int.MAX_VALUE
        var title: String? = null

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 5000) {
            guard++
            val node = stack.removeLast()
            val id = node.viewIdResourceName
            val text = node.text?.toString()
            if (id == BUBBLE_ID && !text.isNullOrBlank()) {
                val b = Rect(); node.getBoundsInScreen(b)
                bubbles.add(Bubble(b.top, b.left, b.right, text))
                if (b.top < firstBubbleTop) firstBubbleTop = b.top
            }
            if (id == TITLE_ID && title == null) text?.let { if (it.isNotBlank()) title = it }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        if (bubbles.isEmpty()) return null

        if (title == null) title = findTitleInActionBar(root, firstBubbleTop, width, res)

        val avatarEdge = (width * 0.13).toInt()
        bubbles.sortBy { it.top }
        val msgs = bubbles.map { b ->
            val dl = kotlin.math.abs(b.left - avatarEdge)
            val dr = kotlin.math.abs((width - avatarEdge) - b.right)
            Msg(if (dr < dl) "me" else "other", b.text)
        }
        return ChatSnapshot(title, msgs)
    }

    private data class Bubble(val top: Int, val left: Int, val right: Int, val text: String)

    companion object {
        private const val BUBBLE_ID = "com.tencent.mobileqq:id/mjn"
        private const val TITLE_ID = "com.tencent.mobileqq:id/371"
    }
}

/** Feishu / Lark (com.ss.android.lark). Nodes are not obfuscated. Plain-text
 *  message bodies render as bare TextViews inside the bubble, so we collect the
 *  message-area text views and drop the chrome (top tabs, title, sender name,
 *  timestamps, system notices, the input box). Sender side = horizontal
 *  position, same as WeChat. */
class FeishuAdapter : ChatAppAdapter {
    override val pkg = "com.ss.android.lark"

    override fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot? {
        val width = res.displayMetrics.widthPixels
        val height = res.displayMetrics.heightPixels
        val topBand = (height * 0.14).toInt()      // action bar + tab row
        val bottomBand = (height * 0.84).toInt()   // input box + keyboard

        var isChat = false
        var title: String? = null
        val items = ArrayList<Triple<Int, Int, String>>() // top, centerX, text

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 6000) {
            guard++
            val node = stack.removeLast()
            val id = node.viewIdResourceName ?: ""
            if (id.endsWith(":id/message") || id.endsWith(":id/bubble_content_container")) isChat = true
            if (id.endsWith(":id/group_name")) node.text?.toString()?.let { if (title == null) title = it }

            val text = node.text?.toString()
            val cls = node.className?.toString()
            if (!text.isNullOrBlank() && cls == "android.widget.TextView" && !isChrome(id) && !looksLikeTimestamp(text)) {
                val b = Rect(); node.getBoundsInScreen(b)
                if (b.top in (topBand + 1) until bottomBand) {
                    items.add(Triple(b.top, b.centerX(), text.trim()))
                }
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        if (!isChat || items.isEmpty()) return null

        items.sortBy { it.first }
        val msgs = items.map { (_, cx, text) ->
            Msg(if (cx > width / 2) "me" else "other", text)
        }
        return ChatSnapshot(title, msgs)
    }

    /** Non-message UI text to skip: title, sender name, time, system notices,
     *  the input EditText. Bodies have no id (bare TextView) so they pass. */
    private fun isChrome(id: String): Boolean =
        id.endsWith(":id/group_name") ||
            id.endsWith(":id/name_tv") ||
            id.endsWith(":id/date_tv") ||
            id.endsWith(":id/system_label") ||
            id.endsWith(":id/kb_rich_text_content") ||
            id.endsWith(":id/thread_title_tv") ||
            id.endsWith(":id/thread_subtitle_tv")
}

/** Trailing "8:11 上午" / "10:29 下午" / "8:11 AM" stamp X glues onto a message. */
private val X_TAIL_TIME = Regex("""\d{1,2}[:：]\d{2}\s*(上午|下午|AM|PM|am|pm)?$""")

/** X uses "。" as a field separator, so a message can end with a run of them. */
private val X_TRAILING_DOTS = Regex("""。+$""")

/**
 * Split one X DM row's contentDescription into (sender, body).
 *
 * "你：你这个说的就是那个虚拟人物，是吗？。8:11 上午。Read。"
 *      → ("你", "你这个说的就是那个虚拟人物，是吗？")
 * "你：他这个东西开源应该问题不大。。。Read。"
 *      → ("你", "他这个东西开源应该问题不大")
 * "All-In：附加的帖子。。"          → ("All-In", "附加的帖子")
 *
 * The sender is everything before the FIRST separator (full-width "：" in the
 * Chinese UI, ": " as a rough fallback elsewhere); the rest is the body plus
 * chrome — the read receipt, the timestamp, and the "。" gluing them on — which
 * is stripped from the tail in that order. Punctuation the user actually typed
 * ("是吗？") survives. Null when there is no separator or nothing is left.
 */
private fun parseXDesc(desc: String): Pair<String, String>? {
    val full = desc.indexOf('：')
    val half = desc.indexOf(": ")
    val cut: Int
    val skip: Int
    when {
        full >= 0 && (half < 0 || full <= half) -> { cut = full; skip = 1 }
        half >= 0 -> { cut = half; skip = 2 }
        else -> return null
    }
    val sender = desc.substring(0, cut).trim()
    var body = desc.substring(cut + skip).trim()
    for (tail in arrayOf("Read。", "Read", "已读。", "已读")) {
        if (body.endsWith(tail)) { body = body.removeSuffix(tail).trim(); break }
    }
    body = X_TRAILING_DOTS.replace(body, "").trim()
    X_TAIL_TIME.find(body)?.let { body = body.substring(0, it.range.first).trim() }
    body = X_TRAILING_DOTS.replace(body, "").trim()
    if (sender.isEmpty() || body.isEmpty()) return null
    return sender to body
}

/**
 * X / Twitter (com.twitter.android) direct messages. Verified on X 12.25.2 /
 * Xiaomi 14 (1200x2670), Chinese system language.
 *
 * The DM thread is Compose UI: each message is a bare `android.view.View` with
 * NO resource-id, full screen width and empty text — the whole message lives in
 * contentDescription ("All-In：重新写了一个😂。10:29 下午。"). The date divider is a
 * TextView with no "：", so filtering on class + full width + a separator keeps
 * it out. An attachment row ("All-In：附加的帖子。。") nests the quoted post's own
 * TextViews; we only take the row View's own desc, never its children.
 *
 * Every screen runs under the same MainActivity, so "are we in a DM thread" can
 * only be answered by the tree: a thread has the message EditText, the DM list
 * does not. The list's rows look similar but read
 * "All-In, @all_in_2026, 你这个说的就是那…", so ", @" is an extra guard.
 *
 * Side comes from the sender label ("你" / "You"), not geometry — every row is
 * full width no matter who spoke.
 */
class XAdapter : ChatAppAdapter {
    override val pkg = "com.twitter.android"

    override fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot? {
        val width = res.displayMetrics.widthPixels
        val rows = ArrayList<Row>()
        var firstRowTop = Int.MAX_VALUE
        var hasInput = false

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 6000) {
            guard++
            val node = stack.removeLast()
            val cls = node.className?.toString()
            if (!hasInput && (node.isEditable || cls == "android.widget.EditText")) hasInput = true

            val desc = node.contentDescription?.toString()
            if (cls == "android.view.View" && !desc.isNullOrBlank() && !desc.contains(", @")) {
                val b = Rect(); node.getBoundsInScreen(b)
                if (b.left == 0 && b.right == width) {
                    val parsed = parseXDesc(desc)
                    if (parsed != null) {
                        rows.add(Row(b.top, parsed.first, parsed.second))
                        if (b.top < firstRowTop) firstRowTop = b.top
                    }
                }
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        // No input box → this is the DM list (or some other X screen), not a chat.
        if (!hasInput || rows.isEmpty()) return null

        // X left-aligns the thread title (x≈300..443 of 1200), so widen the
        // shared helper's "roughly centered" band for this app.
        val title = findTitleInActionBar(root, firstRowTop, width, res, 0.15, 0.85)
        rows.sortBy { it.top }
        val msgs = rows.map { Msg(if (it.sender == "你" || it.sender == "You") "me" else "other", it.text) }
        return ChatSnapshot(title, msgs)
    }

    private data class Row(val top: Int, val sender: String, val text: String)
}
