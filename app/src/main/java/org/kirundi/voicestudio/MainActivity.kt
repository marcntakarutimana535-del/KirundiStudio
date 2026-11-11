package org.kirundi.voicestudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.kirundi.voicestudio.ui.AnalysisScreen
import org.kirundi.voicestudio.ui.LibraryScreen
import org.kirundi.voicestudio.ui.ProjectsScreen
import org.kirundi.voicestudio.ui.PreTestScreen
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
                    val titles = listOf(
                        stringResource(R.string.tab_studio),
                        stringResource(R.string.tab_pretest),
                        stringResource(R.string.tab_analysis),
                        stringResource(R.string.tab_library),
                        stringResource(R.string.tab_projects),
                        stringResource(R.string.tab_probe)
                    )
                    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                            for (i in titles.indices) {
                                Tab(
                                    selected = tab == i,
                                    onClick = { tab = i },
                                    enabled = !busy,
                                    text = { Text(titles[i]) }
                                )
                            }
                        }
                        when (tab) {
                            0 -> StudioScreen(onBusyChange = { busy = it })
                            1 -> PreTestScreen(onBusyChange = { busy = it })
                            2 -> AnalysisScreen()
                            3 -> LibraryScreen()
                            4 -> ProjectsScreen()
                            else -> ProbeScreen()
                        }
                    }
                }
            }
        }
    }
}
