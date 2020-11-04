package com.csteenhuis.spoteelight

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.*
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.mollin.yapi.YeelightDevice
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.android.appremote.api.error.SpotifyDisconnectedException
import com.spotify.protocol.client.Subscription
import com.spotify.protocol.types.Capabilities
import com.spotify.protocol.types.ImageUri
import com.spotify.protocol.types.PlayerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class SpoteelightService: LifecycleService() {
    private var posted = false

    private var mCall: Call? = null
    private var apiCall: Call? = null

    private var playerStateSubscription: Subscription<PlayerState>? = null
    private var capabilitiesSubscription: Subscription<Capabilities>? = null
    private var spotifyAppRemote: SpotifyAppRemote? = null

    private val errorCallback = { throwable: Throwable -> println(throwable) }

    private var currentImageUri: String? = null
    private var device: YeelightDevice? = null

    private var listener: SpoteelightServiceListener? = null

    val CHANNEL_ID by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel("spoteelight-channel", "Spoteelight")
        } else {
            "spoteelight-channel"
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if(intent?.action == "start_service") {
            addNotification()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return SpoteelightServiceBinder()
    }


    fun addNotification() {
        if (posted) {
            return
        }

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.app_name))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        posted = true
    }
    override fun onDestroy() {
        cancelCall()
        disconnect()
        super.onDestroy()
    }

    private fun cancelCall() {
        mCall?.cancel()
    }

    fun subscribeToPlayerState() {
        playerStateSubscription = cancelAndResetSubscription(playerStateSubscription)

        playerStateSubscription = assertAppRemoteConnected()
            .playerApi
            .subscribeToPlayerState()
            .setEventCallback(playerStateEventCallback)
            .setLifecycleCallback(
                object : Subscription.LifecycleCallback {
                    override fun onStart() {
                        println("Event: start")
                    }

                    override fun onStop() {
                        println("Event: end")
                    }
                })
            .setErrorCallback {
                println(it)
            } as Subscription<PlayerState>
    }

    fun subscribeToCapabilities() {
        capabilitiesSubscription = cancelAndResetSubscription(capabilitiesSubscription)

        capabilitiesSubscription = assertAppRemoteConnected()
            .userApi
            .subscribeToCapabilities()
            .setEventCallback { capabilities ->
            }
            .setErrorCallback(errorCallback) as Subscription<Capabilities>

        assertAppRemoteConnected()
            .userApi
            .capabilities
            .setResultCallback { capabilities -> println(capabilities)}
            .setErrorCallback(errorCallback)
    }

    private val playerStateEventCallback = Subscription.EventCallback<PlayerState> { playerState ->
        playerState.track?.imageUri?.raw?.let {
            if(this.currentImageUri != it) {
                this.currentImageUri = it
                onImageChanged()
            }
        }
    }


    private fun onImageChanged() {
        println(currentImageUri)
        currentImageUri?.let { currentImageUri ->
            assertAppRemoteConnected()
                .imagesApi
                .getImage(ImageUri(currentImageUri), com.spotify.protocol.types.Image.Dimension.LARGE)
                .setResultCallback { bitmap ->
                    listener?.onImageChanged(bitmap)

                    try {
                        val bos = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bos)
                        val logging = HttpLoggingInterceptor()
                        logging.setLevel(HttpLoggingInterceptor.Level.BASIC)
                        val client = OkHttpClient.Builder()
                            .addInterceptor(logging)
                            .connectTimeout(60, TimeUnit.SECONDS)
                            .readTimeout(60, TimeUnit.SECONDS)
                            .build()

                        val MEDIA_TYPE_PNG = "image/png".toMediaTypeOrNull()!!

                        val req: RequestBody = MultipartBody.Builder().setType(MultipartBody.FORM)
                            .addFormDataPart(
                                "image",
                                "image.png",
                                RequestBody.create(MEDIA_TYPE_PNG, bos.toByteArray())
                            ).build()

                        val request: Request = Request.Builder()
                            .url(getString(R.string.cloud_function_url))
                            .post(req)
                            .build()

                        apiCall?.cancel()
                        apiCall = client.newCall(request)
                        apiCall?.enqueue(object: Callback {
                            override fun onFailure(call: Call, e: IOException) {
                                println(e.message)
                            }

                            override fun onResponse(call: Call, response: Response) {
                                val responseBody = response.body!!.string()
                                println(responseBody)
                                val json = JSONObject(responseBody)
                                Handler(Looper.getMainLooper()).post {
                                    if (json.has("r") && json.has("g") && json.has("b")) {
                                        val r = json.getInt("r")
                                        val g = json.getInt("g")
                                        val b = json.getInt("b")
                                        println("R: ${r}, G: ${g}, B: ${b}")

                                        listener?.onColorChanged(Color.rgb(r, g, b))
                                        lifecycleScope.launch {
                                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                try {
                                                    device?.setRGB(r, g, b)
                                                } catch (e: java.lang.Exception) {
                                                    println(e.message)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        })

                    } catch (e: Exception) {
                        println(e.message)
                    }
                }
        }
    }


    private suspend fun connectToAppRemote(showAuthView: Boolean): SpotifyAppRemote? =
        suspendCoroutine { cont: Continuation<SpotifyAppRemote> ->
            SpotifyAppRemote.connect(
                application,
                ConnectionParams.Builder(MainActivity.CLIENT_ID)
                    .setRedirectUri(getRedirectUri().toString())
                    .showAuthView(showAuthView)
                    .build(),
                object : Connector.ConnectionListener {
                    override fun onConnected(spotifyAppRemote: SpotifyAppRemote) {
                        cont.resume(spotifyAppRemote)
                        subscribeToCapabilities()
                        subscribeToPlayerState()

                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) {
                                try {
                                    device = YeelightDevice(YEELIGHT_IP)
                                } catch (e: java.lang.Exception) {
                                    println(e.message)
                                }

                                device?.setPower(true)
                            }
                        }
                    }

                    override fun onFailure(error: Throwable) {
                        cont.resumeWithException(error)
                    }
                })
        }


    private fun assertAppRemoteConnected(): SpotifyAppRemote {
        spotifyAppRemote?.let {
            if (it.isConnected) {
                return it
            }
        }
        throw SpotifyDisconnectedException()
    }

    private fun <T : Any?> cancelAndResetSubscription(subscription: Subscription<T>?): Subscription<T>? {
        return subscription?.let {
            if (!it.isCanceled) {
                it.cancel()
            }
            null
        }
    }

    fun connect(showAuthView: Boolean) {
        SpotifyAppRemote.disconnect(spotifyAppRemote)
        lifecycleScope.launch {
            try {
                spotifyAppRemote = connectToAppRemote(showAuthView)
            } catch (error: Throwable) {
                println(error)
            }
        }
    }

    fun disconnect() {
        SpotifyAppRemote.disconnect(spotifyAppRemote)
    }

    private fun getRedirectUri(): Uri? {
        return Uri.Builder()
            .scheme(getString(R.string.com_spotify_sdk_redirect_scheme))
            .authority(getString(R.string.com_spotify_sdk_redirect_host))
            .build()
    }

    fun release() {
        addNotification()
        listener = null
        stopForeground(true)
        stopSelf()
    }

    fun setListener(listener: SpoteelightServiceListener) {
        this.listener = listener
    }

    inner class SpoteelightServiceBinder: Binder() {
        fun getService() : SpoteelightService {
            return this@SpoteelightService
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(channelId: String, channelName: String): String{
        val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_NONE)
        chan.lightColor = Color.BLUE
        chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        service.createNotificationChannel(chan)
        return channelId
    }

    companion object {
        const val NOTIFICATION_ID = 2
        const val YEELIGHT_IP = "192.168.1.9"
    }
}