package org.kirundi.voicestudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.kirundi.voicestudio.ui.ProbeScreen
import org.kirundi.voicestudio.ui.StudioScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var tab by remember { mutableStateOf(0) }
                    var busy by remember { mutableStateOf(false) }
                    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                        TabRow(selectedTabIndex = tab) {
                            Tab(
                                selected = tab == 0,
                                onClick = { tab = 0 },
                                enabled = !busy,
                                text = { Text(stringResource(R.string.tab_studio)) }
                            )
                            Tab(
                                selected = tab == 1,
                                onClick = { tab = 1 },
                                enabled = !busy,
                                text = { Text(stringResource(R.string.tab_probe)) }
                            )
                        }
                        if (tab == 0) {
                            StudioScreen(onBusyChange = { busy = it })
                        } else {
                            ProbeScreen()
                        }
                    }
                }
            }
        }
    }
}
