package com.e.androidwebrtc_sample_kt

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.e.androidwebrtc_sample_kt.databinding.ActivityMainBinding
import org.json.JSONArray
import org.webrtc.EglBase
import org.webrtc.MediaStream

private const val REQUEST_CODE_PERMISSION = 100

class MainActivity : AppCompatActivity() {

    private val eglBase by lazy { EglBase.create() }
    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    private var webRtcClient: WebRtcClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        initView()
        initData()
    }

    private fun initView() {
        binding.onlineUserRecyclerView.layoutManager = LinearLayoutManager(this)

        binding.hostButton.setOnClickListener {
            saveHost2Sp(binding.hostEditText.text.toString())

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                    REQUEST_CODE_PERMISSION
                )
            } else {
                webRtcClient?.onDestroy()
                startWebRTC()
            }
        }

        binding.refreshIdsButton.setOnClickListener { webRtcClient?.refreshIds() }

        binding.localRenderer.apply {
            setEnableHardwareScaler(true)
            init(eglBase.eglBaseContext, null)
        }

        binding.remoteRenderer.apply {
            setEnableHardwareScaler(true)
            init(eglBase.eglBaseContext, null)
        }

    }

    private fun initData() {
        binding.hostEditText.setText(getHostfromSp())
    }

    private fun startWebRTC() {

        webRtcClient = WebRtcClient(
            binding.hostEditText.text.toString(),
            this.application,
            eglBase.eglBaseContext,
            object : WebRtcClient.RtcListener {
                override fun onOnlineIdsChanged(jsonArray: JSONArray) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "已刷新", Toast.LENGTH_SHORT)
                            .show()
                        Log.d("list", jsonArray.toString())
                        val list = jsonArray.toString().jsonToList<OnlineUser>() ?: listOf()
                        binding.onlineUserRecyclerView.adapter =
                            OnlineUserAdapter(list) { webRtcClient?.callByClientId(it) }
                    }
                }

                override fun onCallReady(callId: String) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "已连上服务器", Toast.LENGTH_SHORT).show()
                    }
                    webRtcClient?.startLocalCamera(android.os.Build.MODEL, this@MainActivity)
                }

                override fun onStatusChanged(newStatus: String) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, newStatus, Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onLocalStream(localStream: MediaStream) {
                    localStream.videoTracks[0].addSink(binding.localRenderer)
                }

                override fun onAddRemoteStream(remoteStream: MediaStream, endPoint: Int) {
                    remoteStream.videoTracks[0].addSink(binding.remoteRenderer)
                }

                override fun onRemoveRemoteStream(endPoint: Int) {
                }
            })

    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_CODE_PERMISSION -> {
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    startWebRTC()
                }
            }
        }
    }

    private fun saveHost2Sp(host: String) {
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putString("host", host)
            commit()
        }
    }

    private fun getHostfromSp(): String? {
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        return sharedPref.getString("host", "")
    }

    override fun onPause() {
        super.onPause()
        webRtcClient?.onPause()
    }

    override fun onResume() {
        super.onResume()
        webRtcClient?.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        webRtcClient?.onDestroy()
    }
}
