package com.example.button_test

import android.content.Context
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
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class PatientData(
    val name: String,
    val birth: String,
    val ssid: String
)

data class WifiPayload(
    val locations: Map<String, Int>
)

interface PatientService {
    @POST("api/patient")
    fun registerPatient(@Body data: PatientData): Call<Void>
}

interface WifiService {
    @PUT("api/patient/{phoneMac}")
    fun sendWifiLog(
        @Path("phoneMac") phoneMac: String,
        @Body payload: WifiPayload
    ): Call<Void>
}

class MainActivity : AppCompatActivity() {

    private lateinit var etName: EditText
    private lateinit var etBirth: EditText
    private lateinit var etMac: EditText
    private lateinit var etSsid: EditText  // SSID 입력 EditText
    private lateinit var btnRegister: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnApplySsid: Button // SSID 적용 버튼
    private lateinit var tvStatus: TextView

    private var scanning = false
    private var patientMac: String = "1111"
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var wifiManager: WifiManager
    private lateinit var retrofit: Retrofit
    private lateinit var wifiService: WifiService
    private lateinit var patientService: PatientService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // UI 연결
        etName = findViewById(R.id.etName)
        etBirth = findViewById(R.id.etBirth)
        etMac = findViewById(R.id.etMac)
        etSsid = findViewById(R.id.etSsid) // SSID 입력 필드 연결
        btnRegister = findViewById(R.id.btnRegister)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnApplySsid = findViewById(R.id.btnApplySsid) // SSID 적용 버튼
        tvStatus = findViewById(R.id.tvStatus)

        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        retrofit = Retrofit.Builder()
            .baseUrl("https://api.fpfp.o-r.kr/") // ✅ 정확한 서버 주소
            .client(getUnsafeOkHttpClient())
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        wifiService = retrofit.create(WifiService::class.java)
        patientService = retrofit.create(PatientService::class.java)

        // 환자 등록
        btnRegister.setOnClickListener {
            val name = etName.text.toString()
            val birth = etBirth.text.toString()
            val mac = etMac.text.toString()

            if (name.isBlank() || birth.isBlank() || mac.isBlank()) {
                Toast.makeText(this, "모든 정보를 입력해주세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val patientData = PatientData(name, birth, mac)

            // 로그 출력
            Log.d("REGISTER", "보내는 데이터: $patientData")

            patientService.registerPatient(patientData).enqueue(object : Callback<Void> {
                override fun onResponse(call: Call<Void>, response: Response<Void>) {
                    if (response.isSuccessful) {
                        Toast.makeText(this@MainActivity, "등록 성공", Toast.LENGTH_SHORT).show()
                        patientMac = mac
                        tvStatus.text = "환자 등록 완료. MAC: $patientMac"
                    } else {
                        Toast.makeText(this@MainActivity, "등록 실패: ${response.code()}", Toast.LENGTH_SHORT).show()
                        Log.e("REGISTER", "에러 바디: ${response.errorBody()?.string()}")
                    }
                }

                override fun onFailure(call: Call<Void>, t: Throwable) {
                    Toast.makeText(this@MainActivity, "에러 발생: ${t.message}", Toast.LENGTH_SHORT).show()
                    Log.e("REGISTER", "통신 오류", t)
                }
            })
        }

        // SSID 적용 버튼 클릭 시
        btnApplySsid.setOnClickListener {
            val ssid = etSsid.text.toString()
            if (ssid.isNotBlank()) {
                patientMac = ssid
                tvStatus.text = "SSID 적용 완료. 새로운 SSID: $patientMac"
            } else {
                Toast.makeText(this, "SSID를 입력해주세요", Toast.LENGTH_SHORT).show()
            }
        }

        // 스캔 시작
        btnStart.setOnClickListener {
            if (!scanning) {
                scanning = true
                tvStatus.text = "스캔 시작"
                scanAndSend()
            }
        }

        // 스캔 종료
        btnStop.setOnClickListener {
            scanning = false
            tvStatus.text = "스캔 종료"
        }
    }

    private fun scanAndSend() {
        if (!scanning) return

        val success = wifiManager.startScan()
        if (success) {
            val results: List<ScanResult> = wifiManager.scanResults
            val rssiMap = mutableMapOf<String, Int>()

            for (ap in results) {
                rssiMap[ap.BSSID] = ap.level
            }

            val payload = WifiPayload(locations = rssiMap)

            wifiService.sendWifiLog(patientMac, payload).enqueue(object : Callback<Void> {
                override fun onResponse(call: Call<Void>, response: Response<Void>) {
                    if (response.isSuccessful) {
                        tvStatus.text = "RSSI 전송 성공 (${rssiMap.size}개)"
                    } else {
                        tvStatus.text = "전송 실패 (서버 오류): ${response.code()}"
                    }
                }

                override fun onFailure(call: Call<Void>, t: Throwable) {
                    tvStatus.text = "전송 실패: ${t.message}"
                }
            })
        } else {
            tvStatus.text = "스캔 실패"
        }

        handler.postDelayed({ scanAndSend() }, 100)
    }

    private fun getUnsafeOkHttpClient(): OkHttpClient {
        return try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, SecureRandom())
            val sslSocketFactory = sslContext.socketFactory

            val logging = HttpLoggingInterceptor().apply {
                setLevel(HttpLoggingInterceptor.Level.BODY)
            }

            OkHttpClient.Builder()
                .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .addInterceptor(logging)
                .build()
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }
}
