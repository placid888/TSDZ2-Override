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
import java.util.UUID
import kotlin.random.Random

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    // UI 元件
    private lateinit var tvStatus: TextView
    private lateinit var btnConnect: Button
    private lateinit var switchSimMode: Switch
    private lateinit var layoutSimulation: LinearLayout
    private lateinit var layoutManual: LinearLayout

    // BLE 相關
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    
    private val SERVICE_UUID = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")
    private val CHAR_UUID = UUID.fromString("0000FFF1-0000-1000-8000-00805F9B34FB")
    private val DEVICE_NAME = "TSDZ2_SIM"

    // 物理引擎協程
    private var simulationJob: Job? = null
    private var simTargetCadence = 80
    private var simGradient = 0 // -15 到 25

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 綁定 UI
        tvStatus = findViewById(R.id.tvStatus)
        btnConnect = findViewById(R.id.btnConnect)
        switchSimMode = findViewById(R.id.switchSimMode)
        layoutSimulation = findViewById(R.id.layoutSimulation)
        layoutManual = findViewById(R.id.layoutManual)

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        // 要求權限
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

        // 模式切換邏輯
        switchSimMode.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                layoutManual.visibility = View.GONE
                layoutSimulation.visibility = View.VISIBLE
                startSimulationEngine()
            } else {
                layoutSimulation.visibility = View.GONE
                layoutManual.visibility = View.VISIBLE
                stopSimulationEngine()
            }
        }

        setupManualSliders()
        setupSimulationSliders()
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
                val deviceName = result.device.name
                if (deviceName == DEVICE_NAME) {
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

    // --- 區塊 A：物理引擎 (Coroutine) ---
    private fun startSimulationEngine() {
        simulationJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                // 1. 踏頻加入呼吸感 (±2 RPM)
                var activeCadence = simTargetCadence + Random.nextInt(-2, 3)
                if (simTargetCadence == 0) activeCadence = 0 // 停止踩踏就不加亂數

                // 2. 坡度計算扭力 (基準120 + 坡度權重)
                var activeTorque = 120 + (simGradient * 8) + Random.nextInt(-3, 4)
                if (activeTorque < 120) activeTorque = 120
                if (activeCadence == 0) activeTorque = 0 // 沒踩就沒扭力

                // 3. 齒比與坡度換算車速
                var activeSpeed = (activeCadence * 0.35f) - (simGradient * 0.5f)
                if (activeSpeed < 0f) activeSpeed = 0f

                // 4. 坡度影響溫度
                val activeTemp = if (simGradient > 10) 65 else 38

                // 已修正：加入 P 參數並帶入 simGradient
                val cmd = String.format("C:%d,T:%d,S:%.1f,H:%d,P:%d", 
                    activeCadence, activeTorque, activeSpeed, activeTemp, simGradient)
                sendCommand(cmd)
                
                delay(1000) // 每 1 秒刷新發送一次
            }
        }
    }

    private fun stopSimulationEngine() {
        simulationJob?.cancel()
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
                simGradient = progress - 15 // 將 0~40 映射為 -15% ~ +25%
                tvGradient.text = "當前坡度: $simGradient %"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    // --- 區塊 B：手動惡搞面板 ---
    private fun setupManualSliders() {
        val tvC = findViewById<TextView>(R.id.tvManCadence)
        val sbC = findViewById<SeekBar>(R.id.sbManCadence)
        val tvT = findViewById<TextView>(R.id.tvManTorque)
        val sbT = findViewById<SeekBar>(R.id.sbManTorque)
        val tvS = findViewById<TextView>(R.id.tvManSpeed)
        val sbS = findViewById<SeekBar>(R.id.sbManSpeed)
        val tvE = findViewById<TextView>(R.id.tvManError)
        val sbE = findViewById<SeekBar>(R.id.sbManError)

        val manualListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvC.text = "踏頻 (C): ${sbC.progress}"
                tvT.text = "扭力 (T): ${sbT.progress}"
                tvS.text = "車速 (S): ${sbS.progress} km/h"
                tvE.text = "錯誤碼 (E): ${sbE.progress}"
                
                // 即時發送無呼吸感的死數值
                sendCommand("C:${sbC.progress},T:${sbT.progress},S:${sbS.progress},E:${sbE.progress}")
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
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            bluetoothGatt?.close()
        }
    }
}