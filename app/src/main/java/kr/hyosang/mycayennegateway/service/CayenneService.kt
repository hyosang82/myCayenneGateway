package kr.hyosang.mycayennegateway.service

import android.app.Service
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.util.Log
import org.eclipse.paho.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.*
import java.util.regex.Pattern

class CayenneService : Service() {
    companion object {
        var TAG: String = "CayenneService"

        val KEY_TEMPHUMI_MAC_1 = "temphumi_device_mac_1"
        val KEY_POWER_MAC = "power_device_mac"

        val MSG_SET_TEMPHUMI_MAC_1 = 0x01
        val MSG_GET_TEMPHUMI_MAC_1 = 0x02
        val MSG_GET_TEMPHUMI_SUMMARY_1 = 0x03

        val MSG_SET_POWER_MAC = 0x10

    }

    private var temphumiRunner1: TempHumiRunner? = null
    private var powerRunner: PowerRunner? = null

    private var messenger = Messenger(Handler( {message: Message ->
        when(message.what) {
            MSG_SET_TEMPHUMI_MAC_1 -> updateTempHumiMAC1(message.obj as String)

            MSG_GET_TEMPHUMI_MAC_1 -> {
                val reply = Message.obtain()
                reply.what = MSG_GET_TEMPHUMI_MAC_1
                reply.obj = temphumiRunner1?.getDeviceAddress()
                message.replyTo.send(reply)
            }

            MSG_GET_TEMPHUMI_SUMMARY_1 -> {
                val reply = Message.obtain()
                reply.what = MSG_GET_TEMPHUMI_SUMMARY_1
                reply.obj = temphumiRunner1?.getSummary()
                message.replyTo.send(reply)
            }

            MSG_SET_POWER_MAC -> updatePowerMAC(message.obj as String)
        }

        true
    }))


    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "CayenneService started")

        startSavedDevices()
    }

    override fun onBind(p0: Intent?): IBinder {
        return messenger.binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun startSavedDevices() {
        val pref = getSharedPreferences("service", Context.MODE_PRIVATE)
        if(pref.contains(KEY_TEMPHUMI_MAC_1) && (temphumiRunner1 == null)) {
            val mac = pref.getString(KEY_TEMPHUMI_MAC_1, "")
            temphumiRunner1 = TempHumiRunner(this, mac, "a5d6d530-88dd-11e8-b98d-6b2426cc1856", "e74aef70-7b53-11e8-99f5-3323ff570d09")
            temphumiRunner1?.start()
        }

        if(pref.contains(KEY_POWER_MAC) && (powerRunner == null)) {
            val mac = pref.getString(KEY_POWER_MAC, "")
            powerRunner = PowerRunner(this, mac, "63765a20-8f4b-11e8-9d44-05130b528c6a")
            powerRunner?.start()
        }
    }

    private fun updateTempHumiMAC1(mac: String) {
        val pref = getSharedPreferences("service", Context.MODE_PRIVATE)
        val editor = pref.edit()
        editor.putString(KEY_TEMPHUMI_MAC_1, mac)
        editor.commit()

        startSavedDevices()
    }

    private fun updatePowerMAC(mac: String) {
        val pref = getSharedPreferences("service", Context.MODE_PRIVATE)
        val editor = pref.edit()
        editor.putString(KEY_POWER_MAC, mac)
        editor.commit()

        startSavedDevices()
    }
}

class PowerRunner(context: Context, device: String, cayenneClientID: String) : Thread() {
    private val context = context.applicationContext
    private val deviceAddress = device
    private val cayenneClientID = cayenneClientID

    private var outputStream: OutputStream? = null
    private var reader: ReaderThread? = null

    private var lastRead = System.currentTimeMillis()

    private var lockObj = java.lang.Object()
    private var sendCommands = ArrayList<String>()


    private val persistence = MemoryPersistence()
    private var mqttClient = MqttAndroidClient(context, "tcp://mqtt.mydevices.com:1883", cayenneClientID, persistence)



    private var currentWatt: Double = 0.0
    private var accrueWatt: Double = 0.0
    private var voltage: Double = 0.0
    private var current: Double = 0.0

    override fun run() {
        val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(deviceAddress)
        val socket = device.createInsecureRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))

        socket.connect()

        reader = ReaderThread(socket.inputStream)
        reader?.start()

        outputStream = socket.outputStream

        while(true) {
            if(!mqttClient?.isConnected) {
                connectMqtt()
            }

            sendCommands.add("_rb0_")
            sendCommands.add("_rf0_")
            sendCommands.add("_rc0_")
            sendCommands.add("_rd0_")

            sendNextCommand()

            synchronized(lockObj) {
                lockObj.wait()
            }

            publishData()

            Thread.sleep(5000)
        }
    }

    private fun sendNextCommand() {
        if(sendCommands.count() > 0) {
            val c = sendCommands.first().toByteArray(Charset.forName("US-ASCII"))
            c[0] = 2
            c[c.lastIndex] = 3

            outputStream?.write(c)

            sendCommands.removeAt(0)
        }else {
            synchronized(lockObj) {
                lockObj.notifyAll()
            }
        }
    }

    private fun connectMqtt() {
        Log.d("TEST", "Reconnect MQTT...")

        val options = MqttConnectOptions()
        options.userName = "e74aef70-7b53-11e8-99f5-3323ff570d09"
        options.password = "af8f8c5b73a0f8271378e7105d53aaf0dc609b4c".toCharArray()

        mqttClient.connect(options, null, mqttListener)
    }


    private val mqttListener = object: IMqttActionListener {
        override fun onSuccess(asyncActionToken: IMqttToken?) {
            Log.i("TEST", "MQTT Connected")
        }

        override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
            Log.e("TEST", "Failur: $exception")
        }

    }

    inner class ReaderThread(inputStream: InputStream) : Thread() {
        val inputStream = inputStream
        val buf: ByteArray = ByteArray(256)
        val cmdBuf = ByteArray(256)
        var copyIdx = 0

        override fun run() {
            var read = 0
            var needLen = 0

            while(true) {
                try {
                    read = inputStream.read(buf)
                    if(read > 0) {
                        //append
                        System.arraycopy(buf, 0, cmdBuf, copyIdx, read)
                        copyIdx += read

                        processCommand()
                    }else {
                        Log.d("TEST", "NO READ")
                        break
                    }
                }catch(e: Exception) {
                    Log.w("TEST", Log.getStackTraceString(e))
                }
            }

        }

        private fun processCommand() {
            if(copyIdx >= 4) {
                if (cmdBuf[0].toInt() == 2) {
                    val cmd = String(cmdBuf, 1, 2)
                    val len = cmdBuf[3].toInt() - '0'.toInt()
                    val push = 4 + len + 1

                    if(copyIdx >= push) {
                        val data = String(cmdBuf, 4, len)

                        Log.d("TEST", "Cmd=$cmd, len=$len, data=$data")

                        System.arraycopy(cmdBuf, push, cmdBuf, 0, (copyIdx - push))
                        copyIdx -= push

                        command(cmd, data)

                        sendNextCommand()
                    }
                }
            }
        }

        private fun command(command: String, data: String) {
            when (command) {
                "RB" -> //current watt
                {
                    currentWatt = data.toInt(10).toFloat() / 1000.0
                    Log.d("TEST", "Watt = $currentWatt")
                }

                "RF" -> //monthly accrue
                {
                    accrueWatt = data.toInt(10).toFloat() / 100000.0
                    Log.d("TEST", "Monthly = $accrueWatt")
                }

                "RC" -> //voltage
                {
                    voltage = data.toInt(10).toFloat() / 1000.0
                    Log.d("TEST", "Voltage = $voltage")
                }

                "RD" -> //current
                {
                    current = data.toInt(10).toFloat() / 1000.0
                    Log.d("TEST", "Current = $current")
                }
            }
        }

    }

    private fun publishData() {
        val topic1 = "v1/e74aef70-7b53-11e8-99f5-3323ff570d09/things/$cayenneClientID/data/0"
        val message1 = "pow,w=$currentWatt"
        mqttClient.publish(topic1, MqttMessage(message1.toByteArray(Charset.forName("UTF-8"))))

        val topic2 = "v1/e74aef70-7b53-11e8-99f5-3323ff570d09/things/$cayenneClientID/data/1"
        val message2 = "voltage,v=$voltage"
        mqttClient.publish(topic2, MqttMessage(message2.toByteArray(Charset.forName("UTF-8"))))

        val topic3 = "v1/e74aef70-7b53-11e8-99f5-3323ff570d09/things/$cayenneClientID/data/2"
        val message3 = "energy,kwh=$accrueWatt"
        mqttClient.publish(topic3, MqttMessage(message3.toByteArray(Charset.forName("UTF-8"))))

        val topic4 = "v1/e74aef70-7b53-11e8-99f5-3323ff570d09/things/$cayenneClientID/data/3"
        val message4 = "current,a=$current"
        mqttClient.publish(topic4, MqttMessage(message4.toByteArray(Charset.forName("UTF-8"))))

    }

}

class TempHumiRunner(context: Context, device: String, cayenneClientID: String, cayenneMqttUsername: String) {
    private val context = context.applicationContext
    private val deviceAddress = device

    private val cayenneMqttUsername = cayenneMqttUsername
    private val cayenneClientID = cayenneClientID

    private val uuidServiceBattry = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    private val uuidCharacteristicBattery = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    private val uuidServiceTempHumi = UUID.fromString("226c0000-6476-4566-7562-66734470666d")
    private val uuidCharacteristicTempHumi = UUID.fromString("226caa55-6476-4566-7562-66734470666d")
    private val uuidDescriptorTempHumi = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val persistence = MemoryPersistence()
    private var mqttClient = MqttAndroidClient(context, "tcp://mqtt.mydevices.com:1883", cayenneClientID, persistence)

    private val arrTemp: ArrayList<Float> = ArrayList()
    private val arrHumi: ArrayList<Float> = ArrayList()
    private var batteryLevel = 0
    private var lastTemp: Double = 0.0
    private var lastHumi: Double = 0.0

    private var connGatt: BluetoothGatt? = null

    private var timer = Timer()

    fun start() {
        timer.schedule(HTQueryThread(), 0)
    }

    fun getDeviceAddress() : String {
        return deviceAddress
    }

    fun getSummary() : String {
        return "T=$lastTemp, H=$lastHumi, Batt=$batteryLevel%"
    }

    private fun connectMqtt() {
        Log.d("TEST", "Reconnect MQTT...")

        val options = MqttConnectOptions()
        options.userName = cayenneMqttUsername
        options.password = "af8f8c5b73a0f8271378e7105d53aaf0dc609b4c".toCharArray()

        mqttClient.connect(options, null, mqttListener)
    }

    private val mqttListener = object: IMqttActionListener {
        override fun onSuccess(asyncActionToken: IMqttToken?) {
            Log.i("TEST", "MQTT Connected")
        }

        override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
            Log.e("TEST", "Failur: $exception")
        }

    }

    inner class HTQueryThread : TimerTask() {
        override fun run() {
            if(connGatt == null) {
                connGatt = BluetoothAdapter.getDefaultAdapter()
                        .getRemoteDevice(deviceAddress)
                        ?.connectGatt(context, false, gattCallback)
            }else {
                connGatt?.connect()
            }

            if(!mqttClient.isConnected) {
                connectMqtt()
            }
        }

        private val gattCallback = object: BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                Log.d("TEST", "state changed ${status} -> $newState")

                if(newState == BluetoothProfile.STATE_CONNECTED) {
                    arrTemp.clear()
                    arrHumi.clear()
                    batteryLevel = 0

                    gatt?.discoverServices()
                }else if(newState == BluetoothProfile.STATE_DISCONNECTED) {
                    lastTemp = arrTemp.average()
                    lastHumi = arrHumi.average()

                    Log.i("TEST", "Average temp=$lastTemp, humi=$lastHumi")

                    ReportThread().start()
                    Timer().schedule(HTQueryThread(), 30000)
                }

            }

            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {

                gatt?.readCharacteristic(
                        gatt?.getService(uuidServiceBattry)
                                ?.getCharacteristic(uuidCharacteristicBattery)
                )
            }

            override fun onCharacteristicRead(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
                if(uuidCharacteristicBattery.equals(characteristic?.uuid)) {
                    batteryLevel = characteristic?.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0) ?: 0

                    Log.i("TEST", "Battery level: ${batteryLevel}")

                    val characteristic = gatt?.getService(uuidServiceTempHumi)
                            ?.getCharacteristic(uuidCharacteristicTempHumi)
                    val descriptor = characteristic?.getDescriptor(uuidDescriptorTempHumi)

                    descriptor?.value = byteArrayOf(0x01, 0x00)
                    gatt?.writeDescriptor(descriptor)

                    gatt?.setCharacteristicNotification(characteristic, true)
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
                if(uuidCharacteristicTempHumi.equals(characteristic?.uuid)) {
                    val bytes = characteristic?.value
                    var nullPos = 0

                    for(i in bytes!!.indices) {
                        if(bytes[i].compareTo(0) == 0) {
                            nullPos = i
                            break
                        }
                    }

                    val data = String(bytes, 0, nullPos)

                    val pattern = Pattern.compile("([TH])=([0-9\\.]+)")
                    val matcher = pattern.matcher(data)
                    var idx = 0

                    while(matcher.find(idx)) {
                        val v = matcher.group(2).toFloat()

                        when (matcher.group(1)) {
                            "T" -> {
                                arrTemp.add(v)
                            }

                            "H" -> {
                                arrHumi.add(v)
                            }
                        }

                        idx = matcher.toMatchResult().end()
                    }

                    Log.d("TEST", "Read value $data")
                }
            }
        }

        inner class ReportThread : Thread() {
            override fun run() {
                val topic1 = "v1/$cayenneMqttUsername/things/$cayenneClientID/data/0"
                val message1 = "batt,p=$batteryLevel"
                mqttClient.publish(topic1, MqttMessage(message1.toByteArray(Charset.forName("UTF-8"))))

                val topic2 = "v1/$cayenneMqttUsername/things/$cayenneClientID/data/1"
                val message2 = "temp,c=$lastTemp"
                mqttClient.publish(topic2, MqttMessage(message2.toByteArray(Charset.forName("UTF-8"))))

                val topic3 = "v1/$cayenneMqttUsername/things/$cayenneClientID/data/2"
                val message3 = "rel_hum,p=$lastHumi"
                mqttClient.publish(topic3, MqttMessage(message3.toByteArray(Charset.forName("UTF-8"))))

            }
        }

    }

}