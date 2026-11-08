package ru.neuroscribe.core

/** Bounded UPGMA using a nearest-neighbor chain. No dynamic merge penalties here.
 * Build the complete tree before cutting: reciprocal pairs need not arrive in
 * increasing distance order. IDs are canonicalized by first input occurrence.
 */
fun averageLinkage(input: Array<FloatArray>, threshold: Float, checkpoint: () -> Unit = {}): IntArray {
    val n = input.size
    require(n <= 512 && threshold.isFinite() && threshold in 0f..2f)
    require(input.all { it.size == n && it.all { d -> d.isFinite() && d in 0f..2f } })
    for (i in 0 until n) for (j in 0 until i) require(input[i][j] == input[j][i])
    if (n < 2) return IntArray(n)
    val distances = Array(n) { input[it].copyOf() }
    val sizes = IntArray(n) { 1 }
    val active = BooleanArray(n) { true }
    val nodes = IntArray(n) { it }
    val children = Array(2 * n - 1) { intArrayOf(-1, -1) }
    val heights = FloatArray(2 * n - 1)
    val chain = mutableListOf<Int>()
    var remaining = n
    var nextNode = n
    while (remaining > 1) {
        checkpoint()
        if (chain.isEmpty()) chain += active.indexOfFirst { it }
        val current = chain.last()
        val previous = chain.getOrNull(chain.lastIndex - 1)
        var nearest = previous ?: -1
        var best = if (nearest >= 0) distances[current][nearest] else Float.POSITIVE_INFINITY
        for (candidate in 0 until n) if (active[candidate] && candidate != current && distances[current][candidate] < best) {
            nearest = candidate; best = distances[current][candidate]
        }
        if (nearest != previous) { chain += nearest; continue }
        chain.removeAt(chain.lastIndex); chain.removeAt(chain.lastIndex)
        val keep = minOf(current, nearest)
        val remove = maxOf(current, nearest)
        children[nextNode] = intArrayOf(nodes[keep], nodes[remove])
        heights[nextNode] = best
        nodes[keep] = nextNode++
        val total = sizes[keep] + sizes[remove]
        for (k in 0 until n) if (active[k] && k != keep && k != remove) {
            val distance = (distances[keep][k] * sizes[keep] + distances[remove][k] * sizes[remove]) / total
            distances[keep][k] = distance; distances[k][keep] = distance
        }
        sizes[keep] = total; active[remove] = false; remaining--
    }
    val labels = IntArray(n)
    var label = 0
    fun assign(node: Int, cluster: Int) {
        if (node < n) labels[node] = cluster
        else { assign(children[node][0], cluster); assign(children[node][1], cluster) }
    }
    fun cut(node: Int) {
        if (node < n || heights[node] <= threshold) assign(node, label++)
        else { cut(children[node][0]); cut(children[node][1]) }
    }
    cut(nodes[active.indexOfFirst { it }])
    val canonical = mutableMapOf<Int, Int>()
    return IntArray(n) { canonical.getOrPut(labels[it]) { canonical.size } }
}
