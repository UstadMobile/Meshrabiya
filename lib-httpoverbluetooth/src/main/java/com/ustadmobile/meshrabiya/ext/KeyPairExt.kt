package com.ustadmobile.meshrabiya.ext

import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.security.PrivateKey

fun PrivateKey.toPem(): String {

    val stringWriter = StringWriter()
    val pemWriter = PemWriter(PrintWriter(stringWriter))
    pemWriter.writeObject {
        PemObject("PRIVATE KEY", encoded)
    }
    pemWriter.flush()

    return stringWriter.toString()
}
