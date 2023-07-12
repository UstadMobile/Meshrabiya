package com.ustadmobile.test_app

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.ustadmobile.meshrabiya.MNetLogger
import com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode
import com.ustadmobile.meshrabiya.vnet.randomApipaAddr
import com.ustadmobile.test_app.VNetTestActivity.Companion.UUID_MASK
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.acra.ACRA
import org.acra.config.CoreConfigurationBuilder
import org.acra.config.HttpSenderConfigurationBuilder
import org.acra.data.StringFormat
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.bind
import org.kodein.di.instance
import org.kodein.di.singleton

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
            MNetLoggerImpl()
        }
        bind<Json>() with singleton {
            Json {
                encodeDefaults = true
            }
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

    }
}