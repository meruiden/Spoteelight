package com.csteenhuis.spoteelight

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Resources
import android.graphics.Bitmap
import android.os.*
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.spotify.protocol.client.Subscription
import java.util.*

class MainActivity : AppCompatActivity(), SpoteelightServiceListener {

    private var binder: SpoteelightService.SpoteelightServiceBinder? = null
    private val service: SpoteelightService?
        get() { return binder?.getService() }

    private val connection: ServiceConnection by lazy {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binder = service as? SpoteelightService.SpoteelightServiceBinder
                binder?.getService()?.setListener(this@MainActivity)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                binder = null
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        supportActionBar?.title = String.format(Locale.US, "Spotify Auth %s", com.spotify.sdk.android.auth.BuildConfig.VERSION_NAME);
        findViewById<Button>(R.id.loginButton)?.setOnClickListener {
            onConnectAndAuthorizedClicked()
        }

        if(savedInstanceState == null) {
            try {
                val intent = Intent(this, SpoteelightService::class.java)
                intent.action = "start_service"
                startForegroundService(this, intent)
                this.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            } finally {

            }
        }

    }

    override fun onDestroy() {
        super.onDestroy()
        service?.release()
    }


    fun onConnectClicked() {
        service?.connect(false)
    }

    fun onConnectAndAuthorizedClicked() {
        service?.connect(true)
    }

    fun onDisconnectClicked() {
        service?.disconnect()
    }

    companion object {
        const val CLIENT_ID = "5ba3b1f9ff6946a088ef90c613bef569"
        fun dpToPx(dp: Int): Int {
            return (dp * Resources.getSystem().getDisplayMetrics().density).toInt()
        }

        fun pxToDp(px: Int): Int {
            return (px / Resources.getSystem().getDisplayMetrics().density).toInt()
        }
        fun setMargins(v: View, l: Int, t: Int, r: Int, b: Int) {
            if (v.layoutParams is ViewGroup.MarginLayoutParams) {
                val p: ViewGroup.MarginLayoutParams = v.layoutParams as ViewGroup.MarginLayoutParams
                p.setMargins(l, t, r, b)
                v.requestLayout()
            }
        }

        fun startForegroundService(context: Context, intent: Intent?): ComponentName? {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onColorChanged(color: Int) {
        lifecycleScope.launchWhenResumed {
            findViewById<LinearLayout>(R.id.background)?.setBackgroundColor(color)
        }
    }

    override fun onImageChanged(bitmap: Bitmap) {
        lifecycleScope.launchWhenResumed {
            findViewById<ImageView>(R.id.imageView)?.setImageBitmap(bitmap)
        }
    }
}