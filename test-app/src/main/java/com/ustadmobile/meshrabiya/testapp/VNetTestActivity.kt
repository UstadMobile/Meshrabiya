package com.ustadmobile.meshrabiya.testapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ConnectWithoutContact
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ustadmobile.meshrabiya.testapp.appstate.AppUiState
import com.ustadmobile.meshrabiya.testapp.screens.InfoScreen
import com.ustadmobile.meshrabiya.testapp.screens.LocalVirtualNodeScreen
import com.ustadmobile.meshrabiya.testapp.screens.NeighborNodeListScreen
import com.ustadmobile.meshrabiya.testapp.screens.OpenSourceLicensesScreen
import com.ustadmobile.meshrabiya.testapp.screens.ReceiveScreen
import com.ustadmobile.meshrabiya.testapp.screens.SelectDestNodeScreen
import com.ustadmobile.meshrabiya.testapp.screens.SendFileScreen
import com.ustadmobile.meshrabiya.testapp.theme.HttpOverBluetoothTheme
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.android.closestDI
import org.kodein.di.compose.withDI
import java.net.URLEncoder
import java.util.UUID

class VNetTestActivity : ComponentActivity(), DIAware {

    override val di by closestDI()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            HttpOverBluetoothTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MeshrabiyaTestApp(di)
                }
            }
        }
    }



    companion object {

        val UUID_MASK = UUID.fromString("db803871-2136-4a7a-8859-6fdc28b567b6")

    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshrabiyaTestApp(
    di: DI
) = withDI(di) {
    val navController: NavHostController = rememberNavController()
    var appUiState: AppUiState by remember {
        mutableStateOf(AppUiState())
    }

    var selectedItem: String? by remember {
        mutableStateOf(null)
    }

    val snackbarHostState = remember {
        SnackbarHostState()
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
        topBar = {
            TopAppBar(title = {
                Text(appUiState.title)
            })
        },
        floatingActionButton = {
            if(appUiState.fabState.visible) {
                ExtendedFloatingActionButton(
                    onClick = appUiState.fabState.onClick,
                    icon = {
                        appUiState.fabState.icon?.also {
                            Icon(imageVector = it, contentDescription = null)
                        }
                    },
                    text = {
                        Text(appUiState.fabState.label ?: "")
                    }
                )
            }

        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = navController.currentDestination?.route == "localvirtualnode",
                    label = { Text("This Node") },
                    onClick = {
                        navController.navigate("localvirtualnode")
                    },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.PhoneAndroid,
                            contentDescription = null
                        )
                    }
                )

                NavigationBarItem(
                    selected = navController.currentDestination?.route == "network" ,
                    label = { Text("Network") },
                    onClick = {
                        navController.navigate("neighbornodes")
                    },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.ConnectWithoutContact,
                            contentDescription = null,
                        )
                    }
                )

                NavigationBarItem(
                    selected = selectedItem == "send" ,
                    label = { Text("Send") },
                    onClick = {
                        navController.navigate("send")
                    },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.UploadFile,
                            contentDescription = null,
                        )
                    }
                )

                NavigationBarItem(
                    selected = selectedItem == "receive" ,
                    label = { Text("Receive") },
                    onClick = {
                        navController.navigate("receive")
                    },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                        )
                    }
                )

                NavigationBarItem(
                    selected = selectedItem == "info" ,
                    label = { Text("Info") },
                    onClick = {
                        navController.navigate("Info")
                    },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                        )
                    }
                )
            }
        }

    ) { contentPadding ->
        // Screen content
        Box(
            modifier = Modifier.padding(contentPadding)
        ) {
            AppNavHost(
                navController = navController,
                onSetAppUiState = {
                    appUiState = it
                },
                snackbarHostState = snackbarHostState,
            )
        }
    }
}

@Composable
fun AppNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: String = "localvirtualnode",
    onSetAppUiState: (AppUiState) -> Unit = { },
    snackbarHostState: SnackbarHostState,
){
    NavHost(
        modifier = modifier,
        navController = navController,
        startDestination = startDestination
    ) {
        composable("localvirtualnode") {
            LocalVirtualNodeScreen(
                onSetAppUiState = onSetAppUiState,
                snackbarHostState = snackbarHostState,
            )
        }

        composable("neighbornodes") {
            NeighborNodeListScreen(
                onSetAppUiState = onSetAppUiState,
            )
        }

        composable("send") {
            SendFileScreen(
                onNavigateToSelectReceiveNode = {uri ->
                    navController.navigate("selectdestnode/${URLEncoder.encode(uri.toString(), "UTF-8")}")
                },
                onSetAppUiState = onSetAppUiState,
            )
        }

        composable("selectdestnode/{sendFileUri}") { backStackEntry ->
            val uriToSend = backStackEntry.arguments?.getString("sendFileUri")
                ?: throw IllegalArgumentException("No uri to send")
            SelectDestNodeScreen(
                uriToSend = uriToSend,
                navigateOnDone = {
                    navController.popBackStack()
                },
                onSetAppUiState =  onSetAppUiState,
            )
        }

        composable("receive") {
            ReceiveScreen(
                onSetAppUiState = onSetAppUiState
            )
        }

        composable("info") {
            InfoScreen(
                onSetAppUiState = onSetAppUiState,
                onClickLicenses = {
                    navController.navigate("licenses")
                }
            )
        }

        composable("licenses") {
            OpenSourceLicensesScreen()
        }
    }
}




