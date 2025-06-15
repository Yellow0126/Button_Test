package com.example.button_test

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FallDetectionService : Service(), SensorEventListener {

    companion object {
        const val ACTION_START     = "com.example.button_test.START"
        const val ACTION_STOP      = "com.example.button_test.STOP"
        const val ACTION_STATUS    = "com.example.button_test.STATUS"
        const val ACTION_EMERGENCY = "com.example.button_test.EMERGENCY"
        const val ACTION_NURSE_CALL = "com.example.button_test.ACTION_NURSE_CALL"
        const val EXTRA_SSID       = "ssid"
        const val EXTRA_STATUS     = "status"
        const val EXTRA_TIMESTAMP  = "timestamp"
        private const val CHANNEL_ID = "fall_channel"
        private const val NOTIF_ID   = 1

        var isRunning = false
    }

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private lateinit var vibrator: Vibrator
    private var ssid: String = ""

    // 자이로스코프 데이터 저장용 변수
    private var lastGyroX: Float = 0f
    private var lastGyroY: Float = 0f
    private var lastGyroZ: Float = 0f
    private var lastAcc: Float = 0f
    private var lastTime: Long = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_SSID)?.let { ssid = it }

        when (intent?.action) {
            ACTION_START -> {
                val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("낙상 감지 중")
                    .setContentText("앱이 백그라운드에서도 실행 중입니다.")
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .build()
                startForeground(NOTIF_ID, notification)

                // 센서 초기화
                sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
                accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
                vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

                // 두 센서 모두 등록
                accelerometer?.let {
                    sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                }
                gyroscope?.let {
                    sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                }

                isRunning = true
                sendStatusBroadcast(true)
                Log.d("FallService", "startDetection: 서비스 시작, 센서 등록 완료")
            }

            ACTION_STOP -> {
                try {
                    // 1) 센서 리스너 해제
                    if (::sensorManager.isInitialized) {
                        sensorManager.unregisterListener(this)
                    }

                    // 2) 포그라운드 서비스 중단
                    stopForeground(true)
                    
                    // 3) 상태 알림
                    isRunning = false
                    sendStatusBroadcast(false)
                    Log.d("FallService", "stopDetection: 서비스 중지")
                    
                    // 4) 서비스 완전 종료
                    stopSelf()
                } catch (e: Exception) {
                    Log.e("FallService", "서비스 중지 중 오류 발생", e)
                }
            }

            ACTION_NURSE_CALL -> {
                sendNurseCall()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (isRunning && ::sensorManager.isInitialized) {
                sensorManager.unregisterListener(this)
                isRunning = false
                sendStatusBroadcast(false)
            }
        } catch (e: Exception) {
            Log.e("FallService", "onDestroy 중 오류 발생", e)
        }
    }

    // 센서 이벤트 콜백
    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    val x = it.values[0]
                    val y = it.values[1]
                    val z = it.values[2] - 9.8f
                    val acc = Math.sqrt((x*x + y*y + z*z).toDouble()).toFloat()
                    lastAcc = acc
                }
                Sensor.TYPE_GYROSCOPE -> {
                    val currentTime = System.currentTimeMillis()
                    if (lastTime == 0L) {
                        lastTime = currentTime
                        return
                    }

                    val dt = (currentTime - lastTime) / 1000.0f
                    lastTime = currentTime

                    val gyroX = it.values[0]
                    val gyroY = it.values[1]
                    val gyroZ = it.values[2]

                    // 각속도 변화량 계산
                    val gyroDeltaX = Math.abs(gyroX - lastGyroX)
                    val gyroDeltaY = Math.abs(gyroY - lastGyroY)
                    val gyroDeltaZ = Math.abs(gyroZ - lastGyroZ)

                    // 낙상 감지 조건
                    val isFall = lastAcc > 35 && // 가속도 임계값
                            (gyroDeltaX > 2.0f || gyroDeltaY > 2.0f || gyroDeltaZ > 2.0f) // 각속도 변화량 임계값

                    if (isFall) {
                        vibrate()
                        showToast("낙상 감지!")
                        sendEmergency()
                    }

                    // 현재 값을 이전 값으로 저장
                    lastGyroX = gyroX
                    lastGyroY = gyroY
                    lastGyroZ = gyroZ
                }
            }
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // 진동 함수
    private fun vibrate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(500)
        }
    }

    // 토스트 함수
    private fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // 상태 브로드캐스트
    private fun sendStatusBroadcast(running: Boolean) {
        Intent(FallDetectionService.ACTION_STATUS).apply {
            putExtra(FallDetectionService.EXTRA_STATUS, running)
        }.also { sendBroadcast(it) }
    }
    // 낙상 감지 시 긴급 API 호출
    private fun sendEmergency() {
        if (ssid.isEmpty()) {
            Log.e("FallService", "SSID가 비어있습니다")
            return
        }
        
        val json = """{"ssid":"$ssid"}"""
        Log.d("FallService", "전송할 데이터: $json")
        
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = json.toRequestBody(mediaType)
        val request = Request.Builder()
            .url("https://api.fpfp.o-r.kr/api/emergency")
            .post(body)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .build()

        OkHttpClient().newCall(request).enqueue(object: okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.e("FallService", "Emergency API 실패", e)
                val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                sendBroadcast(Intent(ACTION_EMERGENCY).apply {
                    putExtra(EXTRA_TIMESTAMP, now)
                    putExtra("success", false)
                    putExtra("error_code", "NETWORK_ERROR")
                    putExtra("error_message", e.message ?: "네트워크 오류")
                })
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val responseBody = response.body?.string()
                Log.d("FallService", "서버 응답: $responseBody")
                
                if (response.isSuccessful) {
                    Log.d("FallService", "Emergency API 성공: $now")
                    sendBroadcast(Intent(ACTION_EMERGENCY).apply {
                        putExtra(EXTRA_TIMESTAMP, now)
                        putExtra("success", true)
                    })
                } else {
                    Log.e("FallService", "Emergency API 오류 코드: ${response.code}, 응답: $responseBody")
                    sendBroadcast(Intent(ACTION_EMERGENCY).apply {
                        putExtra(EXTRA_TIMESTAMP, now)
                        putExtra("success", false)
                        putExtra("error_code", response.code.toString())
                        putExtra("error_message", responseBody ?: "서버 오류")
                    })
                }
            }
        })
    }

    // 채널 생성
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "낙상 감지",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    private fun sendNurseCall() {
        if (ssid.isEmpty()) {
            Log.e("FallService", "SSID가 비어있습니다")
            showToast("SSID가 비어있습니다")
            return
        }
        
        val json = """{"ssid":"$ssid"}"""
        Log.d("FallService", "간호사 호출 요청 전송 시작")
        Log.d("FallService", "요청 URL: https://api.fpfp.o-r.kr/api/nurse-call")
        Log.d("FallService", "요청 데이터: $json")
        showToast("간호사 호출 요청 전송 중...")
        
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = json.toRequestBody(mediaType)
        val request = Request.Builder()
            .url("https://api.fpfp.o-r.kr/api/nurse-call")
            .post(body)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .build()

        OkHttpClient().newCall(request).enqueue(object: okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.e("FallService", "간호사 호출 API 실패", e)
                Log.e("FallService", "실패 원인: ${e.message}")
                val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                showToast("간호사 호출 실패: 네트워크 오류")
                sendBroadcast(Intent(ACTION_NURSE_CALL).apply {
                    putExtra(EXTRA_TIMESTAMP, now)
                    putExtra("success", false)
                    putExtra("error_code", "NETWORK_ERROR")
                    putExtra("error_message", e.message ?: "네트워크 오류")
                })
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val responseBody = response.body?.string()
                Log.d("FallService", "간호사 호출 서버 응답 코드: ${response.code}")
                Log.d("FallService", "간호사 호출 서버 응답 내용: $responseBody")
                
                if (response.isSuccessful) {
                    Log.d("FallService", "간호사 호출 API 성공: $now")
                    showToast("간호사 호출 성공")
                    sendBroadcast(Intent(ACTION_NURSE_CALL).apply {
                        putExtra(EXTRA_TIMESTAMP, now)
                        putExtra("success", true)
                    })
                } else {
                    Log.e("FallService", "간호사 호출 API 오류 코드: ${response.code}, 응답: $responseBody")
                    showToast("간호사 호출 실패: ${response.code}")
                    sendBroadcast(Intent(ACTION_NURSE_CALL).apply {
                        putExtra(EXTRA_TIMESTAMP, now)
                        putExtra("success", false)
                        putExtra("error_code", response.code.toString())
                        putExtra("error_message", responseBody ?: "서버 오류")
                    })
                }
            }
        })
    }
}
