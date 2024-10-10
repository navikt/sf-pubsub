package no.nav.sf.pubsub

import com.google.protobuf.ByteString

fun fromEscapedString(escapedString: String): ByteString {
    // Create a mutable byte list to store the byte values
    val byteList = mutableListOf<Byte>()

    // Iterate over the string 4 characters at a time
    var i = 0
    while (i < escapedString.length) {
        if (escapedString[i] == '\\') {
            // Extract the next three characters after the backslash
            val byteValueStr = escapedString.substring(i + 1, i + 4)

            // Convert the extracted string into an octal integer, then cast to byte
            val byteValue = byteValueStr.toInt(8).toByte()

            // Add the byte value to the list
            byteList.add(byteValue)

            // Move forward by 4 characters (\000 is 4 characters)
            i += 4
        }
    }

    // Convert the byte list to a ByteArray and then create the ByteString
    return ByteString.copyFrom(byteList.toByteArray())
}
