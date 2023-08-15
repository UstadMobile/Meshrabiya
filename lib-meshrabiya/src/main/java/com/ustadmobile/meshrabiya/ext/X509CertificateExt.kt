package com.ustadmobile.meshrabiya.ext

import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.security.cert.X509Certificate

fun X509Certificate.toPem(): String {
    val stringWriter = StringWriter()
    val pemWriter = PemWriter(PrintWriter(stringWriter))
    pemWriter.writeObject {
        PemObject("CERTIFICATE", this.encoded)
    }
    pemWriter.flush()


    return stringWriter.toString()
}