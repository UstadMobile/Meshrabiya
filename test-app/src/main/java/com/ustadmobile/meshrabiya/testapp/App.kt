package com.ustadmobile.meshrabiya.testapp

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.ext.asInetAddress
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode
import com.ustadmobile.meshrabiya.vnet.randomApipaAddr
import com.ustadmobile.meshrabiya.testapp.server.TestAppServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.acra.ACRA
import org.acra.config.CoreConfigurationBuilder
import org.acra.config.HttpSenderConfigurationBuilder
import org.acra.data.StringFormat
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.bind
import org.kodein.di.instance
import org.kodein.di.singleton
import java.io.File
import java.net.InetAddress

class App: Application(), DIAware {

    val ADDRESS_PREF_KEY = intPreferencesKey("virtualaddr")

    private val diModule = DI.Module("meshrabiya-module") {

        bind<InetAddress>(tag = TAG_VIRTUAL_ADDRESS) with singleton() {
            runBlocking {
                val addr = applicationContext.dataStore.data.map { preferences ->
                    preferences[ADDRESS_PREF_KEY] ?: 0
                }.first()

                if(addr != 0) {
                    addr.asInetAddress()
                }else {
                    randomApipaAddr().also { randomAddress ->
                        applicationContext.dataStore.edit {
                            it[ADDRESS_PREF_KEY] = randomAddress
                        }
                    }.asInetAddress()
                }
            }
        }

        bind<MNetLogger>() with singleton {
            MNetLoggerAndroid(minLogLevel = Log.DEBUG)
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
                logger = instance(),
                json = instance(),
                address = instance(tag = TAG_VIRTUAL_ADDRESS),
                dataStore = applicationContext.dataStore
            )
        }

        bind<OkHttpClient>() with singleton {
            val node: AndroidVirtualNode = instance()
            OkHttpClient.Builder()
                .socketFactory(node.socketFactory)
                .build()
        }

        bind<TestAppServer>() with singleton {
            val node: AndroidVirtualNode = instance()
            TestAppServer(
                appContext = applicationContext,
                httpClient = instance(),
                mLogger = instance(),
                port = TestAppServer.DEFAULT_PORT,
                name = node.addressAsInt.addressToDotNotation(),
                localVirtualAddr = node.address,
                receiveDir = instance(tag = TAG_RECEIVE_DIR),
                json = instance(),
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