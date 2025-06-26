package com.example.bluetoothtester

import android.content.Context
import android.media.MediaPlayer
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RawRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.toggl.komposable.architecture.Reducer
import com.toggl.komposable.extensions.createStore
import com.toggl.komposable.scope.DispatcherProvider
import com.toggl.komposable.scope.StoreScopeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        bluetoothStore.send(BluetoothAction.ActivityCreated(this))
        bluetoothStore.send(BluetoothAction.ContextCreated(applicationContext))
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
            ) {
                MainApp(
                    context = applicationContext
                )
            }
        }
    }
}

val bluetoothAppReducer: Reducer<BluetoothState, BluetoothAction> = BluetoothReducer()

val dispatcherProvider = DispatcherProvider(
    io = Dispatchers.IO,
    computation = Dispatchers.Default,
    main = Dispatchers.Main,
)
val coroutineScope = object : CoroutineScope {
    override val coroutineContext: CoroutineContext
        get() = dispatcherProvider.main
}
val storeScopeProvider = StoreScopeProvider { coroutineScope }
val bluetoothStore = createStore(
    initialState = BluetoothState(),
    reducer = bluetoothAppReducer,
    storeScopeProvider = storeScopeProvider,
    dispatcherProvider = dispatcherProvider,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BasePage(
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier
            .fillMaxWidth(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "Bluetooth Tester",
                        fontWeight = FontWeight.Bold,
                        color = colorResource(R.color.tertiary_blue)
                    )
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = colorResource(R.color.primary_blue)
                )
            )
        }
    ) { innerPadding ->
        Column(
            horizontalAlignment = Alignment.Start,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            content()
        }
    }
}

fun Context.playSound(@RawRes soundId: Int, onComplete: (() -> Unit)? = null) {
    val player = MediaPlayer.create(this, soundId)
    player.setOnCompletionListener {
        player.release()
        onComplete?.invoke()
    }
    player.start()
}

@Preview
@Composable
fun MainApp(context: Context? = null) {
    val bluetoothState by bluetoothStore.state.collectAsState(initial = BluetoothState())
    BasePage(
        content = {
            BluetoothMain(
                modifier = Modifier
                    .padding(16.dp),
                state = bluetoothState,
                context = context
            )
        }
    )
}