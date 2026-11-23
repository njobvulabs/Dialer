package com.example.ui.screens.apps

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import com.example.ui.components.landscapeContentWidth
import com.example.ui.components.AppIcons
import com.example.ui.components.FloatingTopBar
import com.example.ui.MainViewModel

data class RadioStation(
    val name: String,
    val url: String,
    val description: String
)

@Composable
fun RadioScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    BackHandler { onBack() }

    val stations = listOf(
        RadioStation("Zodiak Broadcasting Station", "https://ice31.securenetsystems.net/0079", "News, talk & music"),
        RadioStation("Capital Radio", "https://node.stream-africa.com:8443/CapitalFM", "Capital FM Malawi"),
        RadioStation("Times Radio", "https://ice31.securenetsystems.net/TIMESFM?playSessionID=EFB80DBC-2E84-4FC8-BE83B0686D7DC37A", "Times Group station"),
        RadioStation("MBC Radio 1", "http://radiostream.mbc.mw:88/broadwavehigh.mp3?src=1", "Malawi Broadcasting Corp"),
        RadioStation("MBC Radio 2 FM", "http://radiostream.mbc.mw:86/broadwavehigh.mp3?src=1", "MBC FM station"),
        RadioStation("Mzati Radio", "http://play.streamafrica.net/mzati", "Chichewa music & talks"),
        RadioStation("Chanco Radio", "https://live.netradio.pro/listen/chanco/radio.mp3", "Uni of Malawi campus radio"),
        RadioStation("BT Synod Radio", "https://play.streamafrica.net/btsynod", "Blantyre Synod station"),
        RadioStation("Trans World Radio (TWR)", "https://iceant.eclipse-streaming.co.za:8443/TWR", "Christian broadcaster")
    )

    var currentStation by remember { mutableStateOf<RadioStation?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(false) }

    // ExoPlayer streams Icecast/redirected/self-signed-cert radio much better than
    // android.media.MediaPlayer. OkHttp lets us trust-all certs and send a browser
    // User-Agent (both required by many stream hosts on non-standard ports).
    val okHttpClient = remember {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAll, SecureRandom())
        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAll[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) nj-dialer Radio/1.0")
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    val player = remember {
        val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply { playWhenReady = true }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING && player.playWhenReady
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (!playing) isBuffering = false
            }

            override fun onPlayerError(error: PlaybackException) {
                isPlaying = false
                isBuffering = false
                Toast.makeText(context, "Cannot play ${currentStation?.name ?: "station"}", Toast.LENGTH_SHORT).show()
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    fun playStation(station: RadioStation) {
        currentStation = station
        isBuffering = true
        isPlaying = false
        player.setMediaItem(MediaItem.fromUri(station.url))
        player.prepare()
        player.play()
    }

    fun onStationClick(station: RadioStation) {
        if (currentStation?.url == station.url) {
            if (player.isPlaying) {
                player.pause()
            } else {
                player.play()
            }
        } else {
            playStation(station)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val useGlassmorphism by viewModel.useGlassmorphism.collectAsState()
        FloatingTopBar(useGlass = useGlassmorphism) {
            Text(
                text = "Internet Radio",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().landscapeContentWidth(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(stations, key = { it.name }) { station ->
                val isCurrent = currentStation?.url == station.url
                val stationPlaying = isCurrent && isPlaying
                val stationBuffering = isCurrent && isBuffering

                Card(
                    onClick = { onStationClick(station) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isCurrent) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.secondaryContainer
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (stationBuffering) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Icon(
                                    imageVector = if (stationPlaying) AppIcons.Pause else AppIcons.PlayArrow,
                                    contentDescription = if (stationPlaying) "Pause" else "Play",
                                    tint = if (isCurrent) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = station.name,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = station.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                        }
                        if (stationPlaying) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = AppIcons.Radio,
                                contentDescription = "Playing",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}