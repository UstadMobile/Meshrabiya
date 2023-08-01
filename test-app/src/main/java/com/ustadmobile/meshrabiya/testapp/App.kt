package com.ustadmobile.meshrabiya.testapp

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode
import com.ustadmobile.meshrabiya.vnet.randomApipaAddr
import com.ustadmobile.meshrabiya.testapp.VNetTestActivity.Companion.UUID_MASK
import com.ustadmobile.meshrabiya.testapp.server.TestAppServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import net.luminis.http3.libnethttp.H3HttpClient
import net.luminis.httpclient.AndroidH3Factory
import net.luminis.tls.env.PlatformMapping
import org.acra.ACRA
import org.acra.config.CoreConfigurationBuilder
import org.acra.config.HttpSenderConfigurationBuilder
import org.acra.data.StringFormat
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.bind
import org.kodein.di.instance
import org.kodein.di.singleton
import java.io.File
import java.security.Security
import java.time.Duration
import java.time.temporal.TemporalUnit
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class App: Application(), DIAware {

    val ADDRESS_PREF_KEY = intPreferencesKey("virtualaddr")

    private val diModule = DI.Module("meshrabiya-module") {

        bind<Int>(tag = TAG_VIRTUAL_ADDRESS) with singleton() {
            runBlocking {
                val addr = applicationContext.dataStore.data.map { preferences ->
                    preferences[ADDRESS_PREF_KEY] ?: 0
                }.first()

                if(addr != 0) {
                    addr
                }else {
                    randomApipaAddr().also { randomAddress ->
                        applicationContext.dataStore.edit {
                            it[ADDRESS_PREF_KEY] = randomAddress
                        }
                    }
                }
            }
        }

        bind<MNetLogger>() with singleton {
            MNetLoggerAndroid(minLogLevel = Log.INFO)
        }
        bind<Json>() with singleton {
            Json {
                encodeDefaults = true
            }
        }

        bind<File>(tag = TAG_WWW_DIR) with singleton {
            File(filesDir, "www").also {
                if(!it.exists())
                    it.mkdirs()
            }
        }

        bind<File>(tag = TAG_RECEIVE_DIR) with singleton {
            File(filesDir, "receive")
        }

        bind<AndroidVirtualNode>() with singleton {
            AndroidVirtualNode(
                appContext = applicationContext,
                uuidMask = UUID_MASK,
                logger = instance(),
                json = instance(),
                localMNodeAddress = instance(tag = TAG_VIRTUAL_ADDRESS),
                dataStore = applicationContext.dataStore
            )
        }

        bind<H3HttpClient>() with singleton {
            val node: AndroidVirtualNode = instance()
            AndroidH3Factory().newClientBuilder()
                .disableCertificateCheck()
                .connectTimeout(Duration.ofSeconds(15))
                .datagramSocketFactory {
                    node.createBoundDatagramSocket(0)
                }
                .build()
        }


        bind<TestAppServer>() with singleton {
            PlatformMapping.usePlatformMapping(PlatformMapping.Platform.Android)
            Security.addProvider(BouncyCastleProvider())

            val node: AndroidVirtualNode = instance()
            val h3Client: H3HttpClient = instance()
            TestAppServer.newTestServerWithRandomKey(
                appContext = applicationContext,
                socket = node.createBoundDatagramSocket(TestAppServer.DEFAULT_PORT),
                h3Factory = AndroidH3Factory(),
                http3Client = h3Client,
                mLogger = instance()
            )
        }

        onReady {
            instance<TestAppServer>().start()
        }

    }

    override val di: DI by DI.lazy {
        import(diModule)
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)

        ACRA.init(this, CoreConfigurationBuilder()
            .withBuildConfigClass(BuildConfig::class.java)
            .withReportFormat(StringFormat.JSON)
            .withLogcatArguments(listOf("-t", "200", "-v", "time"))
            .withPluginConfigurations(
                HttpSenderConfigurationBuilder()
                    .withUri(BuildConfig.ACRA_HTTP_URI)
                    .withBasicAuthLogin(BuildConfig.ACRA_BASIC_LOGIN)
                    .withBasicAuthPassword(BuildConfig.ACRA_BASIC_PASS)
                    .build()
            )
        )
    }


    companion object {

        const val TAG_VIRTUAL_ADDRESS = "virtual_add"

        const val TAG_WWW_DIR = "www_dir"

        const val TAG_RECEIVE_DIR = "receive_dir"

    }
}