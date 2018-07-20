package kr.hyosang.mycayennegateway

import android.Manifest
import android.annotation.TargetApi
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.*
import android.database.DataSetObserver
import android.os.*
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import kotlinx.android.synthetic.main.activity_main.*
import kr.hyosang.mycayennegateway.service.CayenneService
import java.security.Permissions

class MainActivity : Activity() {
    private var serviceMessenger: Messenger? = null
    private var bluetoothDiscoveryList: ArrayAdapter<String>? = null

    private val appMessenger = Messenger(Handler( { message: Message ->
        when(message.what) {
            CayenneService.MSG_GET_TEMPHUMI_MAC_1 ->
                txtTempHumi1MAC.text = message.obj as String

            CayenneService.MSG_GET_TEMPHUMI_SUMMARY_1 ->
                txtTempHumi1Value.text = message.obj as String
        }


        true

    }))


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val intent = Intent(this, CayenneService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        btnPairing.setOnClickListener {
            startPairing()
        }

        bluetoothDiscoveryList = ArrayAdapter(this, android.R.layout.select_dialog_singlechoice)

        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Log.i("TEST", "Request runtime permission")

            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
        }

    }

    override fun onResume() {
        super.onResume()

        var msg = Message.obtain()
        msg.what = CayenneService.MSG_GET_TEMPHUMI_MAC_1
        msg.replyTo = appMessenger
        serviceMessenger?.send(msg)

        msg = Message.obtain()
        msg.what = CayenneService.MSG_GET_TEMPHUMI_SUMMARY_1
        msg.replyTo = appMessenger
        serviceMessenger?.send(msg)

    }

    private fun startPairing() {
        val builder = AlertDialog.Builder(this)
        val dialog = builder.setCancelable(false)
                .setAdapter(bluetoothDiscoveryList, { dialogInterface, i ->
                    Log.i("TEST", "Selected: $i")

                    val addr = bluetoothDiscoveryList?.getItem(i)
                    Log.i("TEST", "Address: $addr")

                    val msg = Message.obtain()
                    msg.what = CayenneService.MSG_SET_TEMPHUMI_MAC_1
                    msg.obj = addr
                    msg.replyTo = appMessenger
                    serviceMessenger?.send(msg)
                })
                .create()

        dialog.show()


        val filter = IntentFilter()
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        filter.addAction(BluetoothDevice.ACTION_FOUND)

        registerReceiver(broadcastReceiver, filter)
        val btAdapter = BluetoothAdapter.getDefaultAdapter()
        btAdapter.startDiscovery()
    }


    private val broadcastReceiver = object: BroadcastReceiver() {
        override fun onReceive(p0: Context?, p1: Intent?) {
            val action = p1?.action

            if(BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)) {
                Log.i("TEST", "DISCOVERY START")
            }else if(BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                Log.i("TEST", "DISCOVERY FINISHED")

                unregisterReceiver(this)
            }else if(BluetoothDevice.ACTION_FOUND.equals(action)) {
                val device = p1?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as BluetoothDevice

                if(device.address.startsWith("4C:65:A8")) {
                    bluetoothDiscoveryList?.add(device.address)
                }

                Log.i("TEST", "FOUND: ${device.name} / ${device.address}")
            }
        }
    }


    private val serviceConnection = object: ServiceConnection {
        override fun onServiceConnected(p0: ComponentName?, p1: IBinder?) {
            Log.i("TEST", "Service connected")

            if(p1 != null) {
                serviceMessenger = Messenger(p1)
            }
        }

        override fun onServiceDisconnected(p0: ComponentName?) {
            Log.i("TEST", "Service disconnected")
        }
    }
}
