package kr.hyosang.mycayennegateway.service

import android.app.Service
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.*
import android.util.Log
import org.eclipse.paho.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.nio.charset.Charset
import java.util.*
import java.util.regex.Pattern

class CayenneService : Service() {
    companion object {
        var TAG: String = "CayenneService"

        var KEY_TEMPHUMI_MAC_1 = "temphumi_device_mac_1"

        val MSG_SET_TEMPHUMI_MAC_1 = 0x01
        val MSG_GET_TEMPHUMI_MAC_1 = 0x02
        val MSG_GET_TEMPHUMI_SUMMARY_1 = 0x03

    }

    private var temphumiRunner1: TempHumiRunner? = null

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
        if(pref.contains(KEY_TEMPHUMI_MAC_1)) {
            val mac = pref.getString(KEY_TEMPHUMI_MAC_1, "")
            temphumiRunner1 = TempHumiRunner(this, mac, "a5d6d530-88dd-11e8-b98d-6b2426cc1856")
            temphumiRunner1?.start()
        }
    }

    private fun updateTempHumiMAC1(mac: String) {
        val pref = getSharedPreferences("service", Context.MODE_PRIVATE)
        val editor = pref.edit()
        editor.putString(KEY_TEMPHUMI_MAC_1, mac)
        editor.commit()

        temphumiRunner1 = TempHumiRunner(this, mac, "a5d6d530-88dd-11e8-b98d-6b2426cc1856")
        temphumiRunner1?.start()
    }
}

class TempHumiRunner(context: Context, device: String, cayenneClientID: String) {
    private val context = context.applicationContext
    private val deviceAddress = device

    private val uuidServiceBattry = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    private val uuidCharacteristicBattery = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    private val uuidServiceTempHumi = UUID.fromString("226c0000-6476-4566-7562-66734470666d")
    private val uuidCharacteristicTempHumi = UUID.fromString("226caa55-6476-4566-7562-66734470666d")
    private val uuidDescriptorTempHumi = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val arrTemp: ArrayList<Float> = ArrayList()
    private val arrHumi: ArrayList<Float> = ArrayList()
    private var batteryLevel = 0

    private var connGatt: BluetoothGatt? = null

    private val persistence = MemoryPersistence()
    private var mqttClient = MqttAndroidClient(context, "tcp://mqtt.mydevices.com:1883", cayenneClientID, persistence)

    private var timer = Timer()

    fun start() {
        timer.schedule(QueryThread(), 0)
    }

    fun getDeviceAddress() : String {
        return deviceAddress
    }

    fun getSummary() : String {
        return "T=${arrTemp.average()}, H=${arrHumi.average()}, Batt=$batteryLevel%"
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


    inner class PublishThread(temp: Float, humi: Float) : Thread() {
        private var temp = temp
        private var humi = humi

        override fun run() {

            val topic1 = "v1/e74aef70-7b53-11e8-99f5-3323ff570d09/things/a5d6d530-88dd-11e8-b98d-6b2426cc1856/data/0"
            val message1 = "batt,p=$batteryLevel"
            mqttClient.publish(topic1, MqttMessage(message1.toByteArray(Charset.forName("UTF-8"))))

            val topic2 = "v1/e74aef70-7b53-11e8-99f5-3323ff570d09/things/a5d6d530-88dd-11e8-b98d-6b2426cc1856/data/1"
            val message2 = "temp,c=$temp"
            mqttClient.publish(topic2, MqttMessage(message2.toByteArray(Charset.forName("UTF-8"))))

            val topic3 = "v1/e74aef70-7b53-11e8-99f5-3323ff570d09/things/a5d6d530-88dd-11e8-b98d-6b2426cc1856/data/2"
            val message3 = "rel_hum,p=$humi"
            mqttClient.publish(topic3, MqttMessage(message3.toByteArray(Charset.forName("UTF-8"))))

        }

    }

    inner class QueryThread : TimerTask() {

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
                    val avgTemp = arrTemp.average()
                    val avgHumi = arrHumi.average()

                    Log.i("TEST", "Average temp=$avgTemp, humi=$avgHumi")

                    PublishThread(avgTemp.toFloat(), avgHumi.toFloat()).start()

                    Timer().schedule(QueryThread(), 30000)
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

    }

}