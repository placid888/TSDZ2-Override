package com.tsdz2.override

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnConnect: Button
    private lateinit var switchSimMode: Switch
    private lateinit var layoutSimulation: LinearLayout
    private lateinit var layoutManual: LinearLayout

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    
    private val SERVICE_UUID = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")
    private val CHAR_UUID = UUID.fromString("0000FFF1-0000-1000-8000-00805F9B34FB")
    private val DEVICE_NAME = "TSDZ2_SIM"

    // 引擎協程
    private var simulationJob: Job? = null
    private var manualJob: Job? = null
    
    private var simTargetCadence = 80
    private var simGradient = 0 

    // 手動模式快取變數
    private var manCadence = 0
    private var manTorque = 0
    private var manSpeed = 0
    private var manError = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        btnConnect = findViewById(R.id.btnConnect)
        switchSimMode = findViewById(R.id.switchSimMode)
        layoutSimulation = findViewById(R.id.layoutSimulation)
        layoutManual = findViewById(R.id.layoutManual)

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        requestBlePermissions()

        btnConnect.setOnClickListener {
            if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
                Toast.makeText(this, "請先開啟手機藍牙", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            tvStatus.text = "狀態：掃描中..."
            tvStatus.setTextColor(android.graphics.Color.parseColor("#FF9800"))
            startBleScan()
        }

        switchSimMode.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                stopManualEngine()
                layoutManual.visibility = View.GONE
                layoutSimulation.visibility = View.VISIBLE
                startSimulationEngine()
            } else {
                stopSimulationEngine()
                layoutSimulation.visibility = View.GONE
                layoutManual.visibility = View.VISIBLE
                startManualEngine()
            }
        }

        setupManualSliders()
        setupSimulationSliders()
        startManualEngine() // 預設啟動手動模式心跳
    }

    private fun requestBlePermissions() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        ActivityCompat.requestPermissions(this, perms, 100)
    }

    private fun startBleScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        scanner?.startScan(object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.device.name == DEVICE_NAME) {
                    scanner.stopScan(this)
                    runOnUiThread { tvStatus.text = "狀態：找到裝置，連線中..." }
                    result.device.connectGatt(this@MainActivity, false, gattCallback)
                }
            }
        })
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread { 
                    tvStatus.text = "狀態：已連線至 TSDZ2_SIM"
                    tvStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
                }
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runOnUiThread { 
                    tvStatus.text = "狀態：連線中斷" 
                    tvStatus.setTextColor(android.graphics.Color.parseColor("#E53935"))
                }
                bluetoothGatt = null
                writeCharacteristic = null
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                bluetoothGatt = gatt
                val service = gatt.getService(SERVICE_UUID)
                writeCharacteristic = service?.getCharacteristic(CHAR_UUID)
                
                // ⚠️ 關鍵修正：請求加大 MTU 封包限制，確保坡度 (P) 不會被截斷
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    gatt.requestMtu(128)
                }
            }
        }
    }

    private fun sendCommand(cmd: String) {
        val charac = writeCharacteristic ?: return
        val gatt = bluetoothGatt ?: return
        
        charac.value = cmd.toByteArray(Charsets.UTF_8)
        charac.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            gatt.writeCharacteristic(charac)
        }
    }

    // --- 區塊 A：物理引擎模式 ---
    private fun startSimulationEngine() {
        simulationJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                var activeCadence = simTargetCadence + Random.nextInt(-2, 3)
                if (simTargetCadence == 0) activeCadence = 0

                var activeTorque = 120 + (simGradient * 8) + Random.nextInt(-3, 4)
                if (activeTorque < 120) activeTorque = 120
                if (activeCadence == 0) activeTorque = 0

                var activeSpeed = (activeCadence * 0.35f) - (simGradient * 0.5f)
                if (activeSpeed < 0f) activeSpeed = 0f

                // 移除不必要的 H 參數，並使用 Locale.US 防止歐洲語系小數點變逗號
                val cmd = String.format(Locale.US, "C:%d,T:%d,S:%.1f,P:%d", 
                    activeCadence, activeTorque, activeSpeed, simGradient)
                sendCommand(cmd)
                delay(1000)
            }
        }
    }

    private fun stopSimulationEngine() {
        simulationJob?.cancel()
    }

    // --- 區塊 B：手動控制模式 ---
    private fun startManualEngine() {
        manualJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                // ⚠️ 關鍵修正：手動模式也加入 1 秒發送 1 次的防超時心跳
                val cmd = "C:$manCadence,T:$manTorque,S:$manSpeed,E:$manError"
                sendCommand(cmd)
                delay(1000)
            }
        }
    }

    private fun stopManualEngine() {
        manualJob?.cancel()
    }

    private fun setupSimulationSliders() {
        val tvCadence = findViewById<TextView>(R.id.tvSimCadence)
        val sbCadence = findViewById<SeekBar>(R.id.sbSimCadence)
        val tvGradient = findViewById<TextView>(R.id.tvSimGradient)
        val sbGradient = findViewById<SeekBar>(R.id.sbSimGradient)

        sbCadence.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                simTargetCadence = progress
                tvCadence.text = "目標踏頻: $progress RPM"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        sbGradient.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                simGradient = progress - 15
                tvGradient.text = "當前坡度: $simGradient %"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupManualSliders() {
        val tvC = findViewById<TextView>(R.id.tvManCadence)
        val sbC = findViewById<SeekBar>(R.id.sbManCadence)
        val tvT = findViewById<TextView>(R.id.tvManTorque)
        val sbT = findViewById<SeekBar>(R.id.sbManTorque)
        val tvS = findViewById<TextView>(R.id.tvManSpeed)
        val sbS = findViewById<SeekBar>(R.id.sbManSpeed)
        val tvE = findViewById<TextView>(R.id.tvManError)
        val sbE = findViewById<SeekBar>(R.id.sbManError)

        manCadence = sbC.progress
        manTorque = sbT.progress
        manSpeed = sbS.progress
        manError = sbE.progress

        val manualListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                manCadence = sbC.progress
                manTorque = sbT.progress
                manSpeed = sbS.progress
                manError = sbE.progress
                
                tvC.text = "踏頻 (C): $manCadence"
                tvT.text = "扭力 (T): $manTorque"
                tvS.text = "車速 (S): $manSpeed km/h"
                tvE.text = "錯誤碼 (E): $manError"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }

        sbC.setOnSeekBarChangeListener(manualListener)
        sbT.setOnSeekBarChangeListener(manualListener)
        sbS.setOnSeekBarChangeListener(manualListener)
        sbE.setOnSeekBarChangeListener(manualListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSimulationEngine()
        stopManualEngine()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            bluetoothGatt?.close()
        }
    }
}