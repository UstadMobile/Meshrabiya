package com.ustadmobile.test_app

import android.app.Application
import android.content.Context
import org.acra.ACRA
import org.acra.config.CoreConfigurationBuilder
import org.acra.config.HttpSenderConfigurationBuilder
import org.acra.data.StringFormat

class App: Application() {

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
}