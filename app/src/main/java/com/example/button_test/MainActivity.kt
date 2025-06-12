package com.example.button_test

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager


// 환자 등록용 데이터 클래스
data class PatientData(
    val name: String,
    val birth: String,
    val ssid: String
)

// 단일 location 전송용 데이터 클래스
data class SingleWifiPayload(
    val locations: Map<String, Int>
)

// 3개 location 전송용 데이터 클래스
data class WifiPayload(
    val location1: Map<String, Int>,
    val location2: Map<String, Int>,
    val location3: Map<String, Int>
)

// 환자 등록 REST API
interface PatientService {
    @POST("api/patient")
    fun registerPatient(@Body data: PatientData): Call<Void>
}

// Wi-Fi 로그 전송 REST API
interface WifiService {
    @PUT("api/patient/{phoneMac}")
    fun sendWifiLog(
        @Path("phoneMac") phoneMac: String,
        @Body payload: SingleWifiPayload
    ): Call<Void>

    @PUT("api/patient/{phoneMac}")
    fun sendWifiLogMulti(
        @Path("phoneMac") phoneMac: String,
        @Body payload: WifiPayload
    ): Call<Void>
}

class MainActivity : AppCompatActivity() {

    // UI 컴포넌트
    private lateinit var etName: EditText
    private lateinit var etBirth: EditText
    private lateinit var etMac: EditText
    private lateinit var etSsid: EditText
    private lateinit var btnRegister: Button
    private lateinit var btnStartScan: Button
    private lateinit var btnStopScan: Button
    private lateinit var btnApplySsid: Button

    // 상태 표시용 TextView
    private lateinit var tvStatus: TextView        // 환자 등록 / Wi-Fi 전송 상태
    private lateinit var tvFallStatus: TextView    // 낙상 감지 상태
    private lateinit var tvServerStatus: TextView  // 서버 긴급 호출 결과

    // 내부 변수
    private var scanning = false
    private var patientMac: String = "1111"
    private val handler = Handler(Looper.getMainLooper())
    
    // RSSI 측정값 저장용 큐 (최대 3개 저장)
    private val rssiQueue = ArrayDeque<Map<String, Int>>(3)

    // 네트워크 / Wi-Fi 스캔
    private lateinit var wifiManager: WifiManager
    private lateinit var retrofit: Retrofit
    private lateinit var wifiService: WifiService
    private lateinit var patientService: PatientService

    // 낙상 감지 제어 버튼
    private lateinit var btnFallStart: Button
    private lateinit var btnFallStop: Button

    // 간호사 호출 수신기
    private val nurseCallReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                val success = it.getBooleanExtra("success", false)
                val errorCode = it.getStringExtra("error_code")
                val errorMessage = it.getStringExtra("error_message")
                val time = it.getStringExtra(FallDetectionService.EXTRA_TIMESTAMP)
                
                val message = if (success) {
                    "서버 상태: [$time] 간호사 호출 완료"
                } else {
                    "서버 상태: [$time] 간호사 호출 실패 ($errorCode)"
                }
                
                tvServerStatus.text = message
                Log.d("MainActivity", "Nurse call response received - Success: $success, Error: $errorCode - $errorMessage")
            }
        }
    }

    // 낙상 감지 상태와 서버 상태를 업데이트하는 브로드캐스트 리시버
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val running = intent?.getBooleanExtra(FallDetectionService.EXTRA_STATUS, false) ?: false
            val time = intent?.getStringExtra(FallDetectionService.EXTRA_TIMESTAMP) ?: ""

            // UI 스레드에서 텍스트 업데이트
            runOnUiThread {
                // 낙상 감지 상태 업데이트
                tvFallStatus.text = if (running) {
                    "감지 상태: 실행 중"
                } else {
                    "감지 상태: 중지됨"
                }

                // 서버 상태 업데이트 (서버 응답 시간 및 성공/실패 여부 포함)
                if (time.isNotEmpty()) {
                    tvServerStatus.text = "$time 전송 완료"
                } else {
                    tvServerStatus.text = "서버 상태: 대기 중"
                }
            }
        }
    }

    // 낙상 감지 후 서버 전송 결과 수신기
    private val emergencyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                val success = it.getBooleanExtra("success", false)
                val errorCode = it.getStringExtra("error_code")
                val errorMessage = it.getStringExtra("error_message")
                val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

                // 서버 상태 UI 업데이트
                tvServerStatus.text = if (success) {
                    "서버 상태: [$time] 전송 완료"
                } else {
                    "서버 상태: [$time] 전송 실패 ($errorCode)"
                }

                // 로그로 응답 확인
                Log.d("MainActivity", "Emergency response received - Success: $success, Error: $errorCode - $errorMessage")
            }
        }
    }

    private var lastRssiMap: Map<String, Int> = emptyMap()  // 이전 스캔 결과 저장
    private var lastScanTime: Long = 0L  // 마지막 스캔 시간

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                val results: List<ScanResult> = wifiManager.scanResults
                if (results.isEmpty()) return

                val currentTime = System.currentTimeMillis()
                val timeDiff = if (lastScanTime == 0L) 0 else currentTime - lastScanTime

                val rssiMap = mutableMapOf<String, Int>()
                for (ap in results) {
                    rssiMap[ap.BSSID] = ap.level
                }

                // 이전 결과와 비교하여 실제로 변경된 경우에만 처리
                if (rssiMap != lastRssiMap) {
                    lastRssiMap = rssiMap
                    lastScanTime = currentTime

                    // ===== 단일 location 전송 방식 시작 =====
                    /*
                    val singlePayload = SingleWifiPayload(
                        locations = rssiMap
                    )

                    // 전송할 데이터 로그 출력 (단일 location)
                    Log.d("WifiScan", "전송할 데이터:")
                    Log.d("WifiScan", "locations: ${singlePayload.locations}")

                    // Wi-Fi 로그 전송 (단일 location)
                    wifiService.sendWifiLog(patientMac, singlePayload).enqueue(object : Callback<Void> {
                        override fun onResponse(call: Call<Void>, response: Response<Void>) {
                            if (response.isSuccessful) {
                                tvStatus.text = "RSSI 전송 성공 (${rssiMap.size}개, 스캔 간격: ${timeDiff}ms)"
                                tvServerStatus.text = "서버 상태: 전송 완료"
                            } else {
                                tvStatus.text = "RSSI 전송 실패 (${rssiMap.size}개, 스캔 간격: ${timeDiff}ms)"
                                tvServerStatus.text = "서버 오류: ${response.code()}"
                            }
                        }

                        override fun onFailure(call: Call<Void>, t: Throwable) {
                            tvStatus.text = "RSSI 전송 실패 (${rssiMap.size}개, 스캔 간격: ${timeDiff}ms)"
                            tvServerStatus.text = "서버 통신 실패: ${t.message}"
                        }
                    })
                    */
                    // ===== 단일 location 전송 방식 끝 =====

                    // ===== 3개 location 전송 방식 시작 =====
                    // 새로운 측정값을 큐에 추가
                    rssiQueue.addFirst(rssiMap)
                    // 큐 크기를 3으로 유지
                    while (rssiQueue.size > 3) {
                        rssiQueue.removeLast()
                    }

                    // 3개가 모두 채워졌을 때만 전송
                    if (rssiQueue.size == 3) {
                        val payload = WifiPayload(
                            location1 = rssiQueue.elementAtOrNull(0) ?: emptyMap(),
                            location2 = rssiQueue.elementAtOrNull(1) ?: emptyMap(),
                            location3 = rssiQueue.elementAtOrNull(2) ?: emptyMap()
                        )

                        // 전송할 데이터 로그 출력 (3개 location)
                        Log.d("WifiScan", "전송할 데이터:")
                        Log.d("WifiScan", "스캔 간격: ${timeDiff}ms")
                        Log.d("WifiScan", "location1 (현재): ${payload.location1}")
                        Log.d("WifiScan", "location2 (이전): ${payload.location2}")
                        Log.d("WifiScan", "location3 (이전이전): ${payload.location3}")

                        // Wi-Fi 로그 전송
                        wifiService.sendWifiLogMulti(patientMac, payload).enqueue(object : Callback<Void> {
                            override fun onResponse(call: Call<Void>, response: Response<Void>) {
                                if (response.isSuccessful) {
                                    tvStatus.text = "RSSI 전송 성공 (${rssiMap.size}개, 스캔 간격: ${timeDiff}ms)"
                                    tvServerStatus.text = "서버 상태: 전송 완료"
                                } else {
                                    tvStatus.text = "RSSI 전송 실패 (${rssiMap.size}개, 스캔 간격: ${timeDiff}ms)"
                                    tvServerStatus.text = "서버 오류: ${response.code()}"
                                }
                            }

                            override fun onFailure(call: Call<Void>, t: Throwable) {
                                tvStatus.text = "RSSI 전송 실패 (${rssiMap.size}개, 스캔 간격: ${timeDiff}ms)"
                                tvServerStatus.text = "서버 통신 실패: ${t.message}"
                            }
                        })
                    }
                    // ===== 3개 location 전송 방식 끝 =====
                }

                // 스캔이 완료되면 바로 다음 스캔 시작
                if (scanning) {
                    wifiManager.startScan()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 뷰 바인딩
        etName         = findViewById(R.id.etName)
        etBirth        = findViewById(R.id.etBirth)
        etMac          = findViewById(R.id.etMac)
        etSsid         = findViewById(R.id.etSsid)
        btnRegister    = findViewById(R.id.btnRegister)
        btnStartScan   = findViewById(R.id.btnStart)
        btnStopScan    = findViewById(R.id.btnStop)
        btnApplySsid   = findViewById(R.id.btnApplySsid)

        tvStatus       = findViewById(R.id.tvStatus)
        tvFallStatus   = findViewById(R.id.tvFallStatus)
        tvServerStatus = findViewById(R.id.tvServerStatus)

        btnFallStart   = findViewById(R.id.btnFallStart)
        btnFallStop    = findViewById(R.id.btnFallStop)

        // Retrofit & 서비스 인터페이스 초기화
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        retrofit = Retrofit.Builder()
            .baseUrl("https://api.fpfp.o-r.kr/")
            .client(getUnsafeOkHttpClient())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        wifiService    = retrofit.create(WifiService::class.java)
        patientService = retrofit.create(PatientService::class.java)

        // 환자 등록 버튼
        btnRegister.setOnClickListener {
            val name  = etName.text.toString().trim()
            val birth = etBirth.text.toString().trim()
            val mac   = etMac.text.toString().trim()
            if (name.isEmpty() || birth.isEmpty() || mac.isEmpty()) {
                Toast.makeText(this, "모든 정보를 입력해주세요", Toast.LENGTH_SHORT).show()
            } else {
                patientService.registerPatient(PatientData(name, birth, mac))
                    .enqueue(object : Callback<Void> {
                        override fun onResponse(call: Call<Void>, response: Response<Void>) {
                            if (response.isSuccessful) {
                                Toast.makeText(
                                    this@MainActivity, "등록 성공", Toast.LENGTH_SHORT
                                ).show()
                                patientMac = mac
                                tvStatus.text = "환자 등록 완료. MAC: $patientMac"
                            } else {
                                tvStatus.text = "등록 실패: ${response.code()}"
                            }
                        }
                        override fun onFailure(call: Call<Void>, t: Throwable) {
                            tvStatus.text = "등록 오류: ${t.message}"
                        }
                    })
            }
        }

        // SSID 교체 버튼
        btnApplySsid.setOnClickListener {
            val ssidText = etSsid.text.toString().trim()
            if (ssidText.isEmpty()) {
                Toast.makeText(this, "SSID를 입력해주세요", Toast.LENGTH_SHORT).show()
            } else {
                patientMac = ssidText
                tvStatus.text = "SSID 적용 완료: $patientMac"
            }
        }

        // Wi-Fi 스캔 시작/종료
        btnStartScan.setOnClickListener {
            scanning = true
            tvStatus.text = "스캔 시작"
            // scanAndSend() 대신 직접 스캔 시작
            wifiManager.startScan()
        }
        btnStopScan.setOnClickListener {
            scanning = false
            tvStatus.text = "스캔 종료"
        }

        // 낙상 감지 시작/종료 버튼
        btnFallStart.setOnClickListener {
            ContextCompat.startForegroundService(
                this,
                Intent(this, FallDetectionService::class.java).apply {
                    action = FallDetectionService.ACTION_START
                    putExtra(FallDetectionService.EXTRA_SSID, patientMac)
                }
            )
            // 낙상 감지 상태는 리시버가 업데이트
            tvServerStatus.text = "서버 상태: 대기 중"
        }
        btnFallStop.setOnClickListener {
            stopService(Intent(this, FallDetectionService::class.java).apply {
                action = FallDetectionService.ACTION_STOP
            })
        }

        // 간호사 호출 버튼
        findViewById<Button>(R.id.btnNurseCall).setOnClickListener {
            val intent = Intent(this, FallDetectionService::class.java).apply {
                action = FallDetectionService.ACTION_NURSE_CALL
                putExtra(FallDetectionService.EXTRA_SSID, patientMac)  // SSID를 patientmac으로 변경
            }
            startService(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        // 상태 수신기 등록
        registerReceiver(statusReceiver, IntentFilter(FallDetectionService.ACTION_STATUS))
        // 낙상 감지 후 서버 전송 결과 수신기 등록
        registerReceiver(emergencyReceiver, IntentFilter(FallDetectionService.ACTION_EMERGENCY))
        // 간호사 호출 수신기 등록
        registerReceiver(nurseCallReceiver, IntentFilter(FallDetectionService.ACTION_NURSE_CALL))
        // Wi-Fi 스캔 결과 수신기 등록
        registerReceiver(wifiScanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statusReceiver)
        unregisterReceiver(emergencyReceiver)
        unregisterReceiver(nurseCallReceiver)
        unregisterReceiver(wifiScanReceiver)
    }

    // Retrofit SSL 우회 클라이언트 (테스트용)
    private fun getUnsafeOkHttpClient(): OkHttpClient {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(ca: Array<out X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(ca: Array<out X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAll, SecureRandom())
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAll[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            })
            .build()
    }
}
