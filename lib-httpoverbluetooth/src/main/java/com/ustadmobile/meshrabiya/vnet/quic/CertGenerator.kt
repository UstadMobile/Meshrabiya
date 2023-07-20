package com.ustadmobile.meshrabiya.vnet.quic

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date


//See https://gist.github.com/alessandroleite/fa3e763552bb8b409bfa
// See also: https://www.programcreek.com/java-api-examples/?api=org.bouncycastle.cert.X509v3CertificateBuilder

fun generateKeyPair() : KeyPair {
    val keyGenerator = KeyPairGenerator.getInstance("RSA")
    keyGenerator.initialize(2048, SecureRandom())
    val keyPair = keyGenerator.generateKeyPair()

    return keyPair
}

fun generateX509Cert(
    keyPair: KeyPair,
    startDate: Date = Date(),
    endDate: Date = Calendar.getInstance().let {
        it.set(Calendar.YEAR, it.get(Calendar.YEAR) + 10)

        Date(it.timeInMillis)
    },
    issuerName: X500Name = X500Name("CN=Meshrabiya"),
    subjectName: X500Name = X500Name("CN=Meshrabiya, OU=Mesh Net, O=UstadMobile FZLLC, L=Dubai, C=AE"),
) : X509Certificate{
    val subjectPublicKeyInfo =  SubjectPublicKeyInfo.getInstance(keyPair.public.encoded)
    val certBuilder = X509v3CertificateBuilder(
        issuerName,
        BigInteger.valueOf(System.currentTimeMillis()),
        startDate,
        endDate,
        subjectName,
        subjectPublicKeyInfo
    )
    val signer = JcaContentSignerBuilder("SHA256WithRSA")
        .setProvider(BouncyCastleProvider())
        .build(keyPair.private)

    val certHolder = certBuilder.build(signer)
    val cert = JcaX509CertificateConverter()
        .setProvider(BouncyCastleProvider())
        .getCertificate(certHolder)

    return cert
}

