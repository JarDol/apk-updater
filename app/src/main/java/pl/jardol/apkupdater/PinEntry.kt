package pl.jardol.apkupdater

internal class PinEntry(private val pin: String) {
    enum class Result { INCOMPLETE, MATCH, WRONG }

    private val entered = StringBuilder()
    val length: Int get() = entered.length

    fun add(digit: Int): Result {
        require(digit in 0..9)
        entered.append(digit)
        if (entered.length < pin.length) return Result.INCOMPLETE
        val result = if (entered.toString() == pin) Result.MATCH else Result.WRONG
        entered.clear()
        return result
    }

    fun delete() {
        if (entered.isNotEmpty()) entered.deleteCharAt(entered.length - 1)
    }
}
