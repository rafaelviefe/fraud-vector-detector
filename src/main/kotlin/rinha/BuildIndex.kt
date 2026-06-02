package rinha

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import kotlin.random.Random

const val DIMENSIONS = 14
const val MAX_VECTORS = 3_000_000

fun main() {
    val inputFile = File("resources/references.json.gz")
    val outputFile = File("resources/index.bin")

    val vectorsShort = ShortArray(MAX_VECTORS * DIMENSIONS)
    val labels = ByteArray(MAX_VECTORS)
    val indices = IntArray(MAX_VECTORS)

    var count = 0

    GZIPInputStream(FileInputStream(inputFile)).bufferedReader().use { reader ->
        var insideString = false
        var tempFraud = false
        var hasVector = false
        
        val keyBuf = CharArray(64)
        var keyLen = 0
        
        var c = reader.read()
        while (c != -1 && count < MAX_VECTORS) {
            val char = c.toChar()
            if (char == '{') {
                hasVector = false
                tempFraud = false
            } else if (char == '}') {
                if (hasVector) {
                    labels[count] = if (tempFraud) 1 else 0
                    indices[count] = count
                    count++
                    hasVector = false
                }
            } else if (char == '"') {
                insideString = !insideString
                if (insideString) {
                    keyLen = 0
                    c = reader.read()
                    while (c != -1 && c.toChar() != '"') {
                        if (keyLen < 64) keyBuf[keyLen++] = c.toChar()
                        c = reader.read()
                    }
                    insideString = false
                    
                    if (keyLen == 5 && keyBuf[0] == 'l' && keyBuf[1] == 'a' && keyBuf[2] == 'b' && keyBuf[3] == 'e' && keyBuf[4] == 'l') {
                        c = reader.read()
                        while (c != -1 && c.toChar() != ':') c = reader.read()
                        c = reader.read()
                        while (c != -1 && (c.toChar().isWhitespace() || c.toChar() == '"')) c = reader.read()
                        
                        tempFraud = (c.toChar() == 'f')
                        
                        while (c != -1 && c.toChar() != ',' && c.toChar() != '}') {
                            c = reader.read()
                        }
                        if (c.toChar() == '}') {
                            if (hasVector) {
                                labels[count] = if (tempFraud) 1 else 0
                                indices[count] = count
                                count++
                                hasVector = false
                            }
                        }
                        continue 
                    } else if (keyLen == 6 && keyBuf[0] == 'v' && keyBuf[1] == 'e' && keyBuf[2] == 'c' && keyBuf[3] == 't' && keyBuf[4] == 'o' && keyBuf[5] == 'r') {
                        c = reader.read()
                        while (c != -1 && c.toChar() != '[') c = reader.read()
                        
                        var dim = 0
                        var isNeg = false
                        var intPart = 0
                        var fracPart = 0f
                        var divisor = 10f
                        var inNumber = false
                        var inFrac = false

                        c = reader.read()
                        while (c != -1 && c.toChar() != ']') {
                            val ch = c.toChar()
                            if (ch == ',' || ch.isWhitespace()) {
                                if (inNumber) {
                                    var f = intPart.toFloat() + fracPart
                                    if (isNeg) f = -f
                                    vectorsShort[count * DIMENSIONS + dim] = Math.round(f * 10000.0).toInt().toShort()
                                    dim++
                                    isNeg = false; intPart = 0; fracPart = 0f; divisor = 10f; inNumber = false; inFrac = false
                                }
                            } else if (ch == '-') {
                                isNeg = true
                                inNumber = true
                            } else if (ch == '.') {
                                inFrac = true
                                inNumber = true
                            } else if (ch in '0'..'9') {
                                inNumber = true
                                val digit = ch - '0'
                                if (inFrac) {
                                    fracPart += digit / divisor
                                    divisor *= 10f
                                } else {
                                    intPart = intPart * 10 + digit
                                }
                            }
                            c = reader.read()
                        }
                        if (inNumber && dim < DIMENSIONS) {
                            var f = intPart.toFloat() + fracPart
                            if (isNeg) f = -f
                            vectorsShort[count * DIMENSIONS + dim] = Math.round(f * 10000.0).toInt().toShort()
                        }
                        hasVector = true
                    }
                }
            }
            c = reader.read()
        }
    }

    val treeLeft = IntArray(count) { -1 }
    val treeRight = IntArray(count) { -1 }
    val treeSplitDim = ByteArray(count)
    val treeNodeId = IntArray(count)
    var nextNode = 0

    val random = Random(42)

    fun quickselect(from: Int, to: Int, target: Int, splitDim: Int) {
        var l = from
        var r = to
        while (r - l > 1) {
            val pivotIdx = l + random.nextInt(r - l)
            val pivotVal = vectorsShort[indices[pivotIdx] * 14 + splitDim]
            
            var less = l
            var cur = l
            var greater = r
            
            while (cur < greater) {
                val v = vectorsShort[indices[cur] * 14 + splitDim]
                if (v < pivotVal) {
                    val t = indices[less]
                    indices[less] = indices[cur]
                    indices[cur] = t
                    less++
                    cur++
                } else if (v > pivotVal) {
                    greater--
                    val t = indices[greater]
                    indices[greater] = indices[cur]
                    indices[cur] = t
                } else {
                    cur++
                }
            }
            
            if (target < less) r = less
            else if (target >= greater) l = greater
            else return
        }
    }

    fun chooseSplitDim(from: Int, to: Int): Int {
        val sz = to - from
        val step = Math.max(1, sz / 256)
        var bestDim = 0
        var maxRange = -1.0f
        
        for (d in 0 until 14) {
            var min = Float.MAX_VALUE
            var max = -Float.MAX_VALUE
            var i = from
            while (i < to) {
                val v = vectorsShort[indices[i] * 14 + d].toFloat()
                if (v < min) min = v
                if (v > max) max = v
                i += step
            }
            val range = max - min
            if (range > maxRange) {
                maxRange = range
                bestDim = d
            }
        }
        return bestDim
    }

    fun buildKD(from: Int, to: Int, depth: Int): Int {
        if (from >= to) return -1
        
        val splitDim = chooseSplitDim(from, to)
        
        val mid = from + (to - from) / 2
        quickselect(from, to, mid, splitDim)
        
        val nodeIdx = nextNode++
        treeSplitDim[nodeIdx] = splitDim.toByte()
        treeNodeId[nodeIdx] = indices[mid]
        
        treeLeft[nodeIdx] = buildKD(from, mid, depth + 1)
        treeRight[nodeIdx] = buildKD(mid + 1, to, depth + 1)
        
        return nodeIdx
    }

    buildKD(0, count, 0)

    val channel = FileOutputStream(outputFile).channel
    val buffer = ByteBuffer.allocateDirect(1024 * 1024 * 16)
    buffer.order(ByteOrder.LITTLE_ENDIAN)

    buffer.putInt(nextNode)

    for (i in 0 until nextNode) {
        if (buffer.remaining() < 42) {
            buffer.flip()
            while (buffer.hasRemaining()) channel.write(buffer)
            buffer.clear()
        }
        buffer.putInt(treeLeft[i])
        buffer.putInt(treeRight[i])
        buffer.put(treeSplitDim[i])
        val origId = treeNodeId[i]
        buffer.put(labels[origId])
        buffer.putInt(origId)
        val vecOff = origId * 14
        for (d in 0 until 14) {
            buffer.putShort(vectorsShort[vecOff + d])
        }
    }
    buffer.flip()
    while (buffer.hasRemaining()) channel.write(buffer)
    channel.close()
}