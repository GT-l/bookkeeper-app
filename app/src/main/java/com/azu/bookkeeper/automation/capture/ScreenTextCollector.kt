package com.azu.bookkeeper.automation.capture

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 屏幕节点文本采集与规范化（v1.3.1 起为**两条识屏链路的唯一入口**）。
 *
 * 为什么必须收敛到一个类：
 * 1. v1.3.0 的自动识屏在遍历后 `child.recycle()`，而它会对 event.source /
 *    rootInActiveWindow / getWindows() 拿到的多个根节点依次遍历 —— 这些 root 常常
 *    **共享同一棵子树**。第一次遍历把子节点回收后，后续遍历只剩根节点自身那一两行
 *    文本（实测只剩「支付」）。摇一摇用的 readScreenSnapshot() 不 recycle，
 *    于是同一页面两条链路读到的内容完全不同。
 * 2. 多根节点直接字符串相加会让同一行重复 3~4 次，6000 字上限被噪声吃满，
 *    真正的金额/商户行反而在后面被截断。
 *
 * 现在统一：整页优先 + 按行去重合并 + 不回收共享节点 + 按包名补齐结构化标签行。
 */
object ScreenTextCollector {

    const val MAX_DEPTH = 32
    const val MAX_TEXT_LENGTH = 6000
    private const val MAX_LINES = 400

    /** 采集单棵节点树，返回按行去重后的纯文本快照。绝不 recycle 任何节点。 */
    fun collect(root: AccessibilityNodeInfo?): String = runCatching {
        if (root == null) return ""
        val lines = LinkedHashSet<String>()
        walk(root, lines, 0)
        merge(lines)
    }.getOrDefault("")

    private fun walk(node: AccessibilityNodeInfo, out: LinkedHashSet<String>, depth: Int) {
        if (depth > MAX_DEPTH || out.size >= MAX_LINES) return
        val primary = runCatching { node.text?.toString()?.trim().orEmpty() }.getOrDefault("")
        val secondary = runCatching { node.contentDescription?.toString()?.trim().orEmpty() }.getOrDefault("")
        if (primary.isNotEmpty()) out.add(primary)
        if (secondary.isNotEmpty() && secondary != primary) out.add(secondary)
        val children = runCatching { node.childCount }.getOrDefault(0)
        for (i in 0 until children) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            walk(child, out, depth + 1)
            // 不 recycle：这些节点可能来自共享子树，还要被其它 root 再读一次。
            // 系统会在窗口销毁时统一回收，Java 层重复 recycle 是 v1.3.0 的回归源头。
        }
    }

    /** 多根节点 / 多段文本合并：按行 trim、去空、去重、保序、限长。 */
    fun merge(parts: Collection<String>): String {
        val seen = LinkedHashSet<String>()
        for (part in parts) {
            for (line in part.lines()) {
                val t = line.trim()
                if (t.isEmpty() || seen.size >= MAX_LINES) continue
                seen.add(t)
            }
        }
        val sb = StringBuilder()
        for (line in seen) {
            if (sb.length >= MAX_TEXT_LENGTH) break
            sb.append(line).append('\n')
        }
        return sb.toString().take(MAX_TEXT_LENGTH)
    }

    /** 单个根节点也要过一遍规范化（去掉空行与重复行），保证与多根路径输出一致。 */
    fun normalize(text: String): String = merge(listOf(text))

    /** 文本稀疏到不足以解析：有效行 < 3 或总长 < 8，触发有界重读。 */
    fun isSparse(text: String): Boolean {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return lines.size < 3 || text.length < 8
    }

    /** 按包名补齐结构化标签行；自动识屏与摇一摇必须调用同一入口。 */
    fun refine(packageName: String, text: String): String {
        val pkg = PaymentScreenRecognizer.normalizePackage(packageName)
        return when (pkg) {
            PaymentScreenRecognizer.WECHAT -> PaymentScreenRecognizer.refineWeChatText(text)
            PaymentScreenRecognizer.DOUYIN -> PaymentScreenRecognizer.refineDouyinText(text)
            else -> text
        }
    }

    /** 诊断预览：把前若干字压成单行，方便复制到 log 里看「到底读到了什么」。 */
    fun preview(text: String, limit: Int = 60): String =
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            .take(6).joinToString("|").take(limit)
}
