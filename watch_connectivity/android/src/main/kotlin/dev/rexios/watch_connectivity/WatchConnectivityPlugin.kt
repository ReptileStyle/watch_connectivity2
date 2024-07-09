package dev.rexios.watch_connectivity

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import com.google.android.gms.wearable.*
import com.google.android.gms.wearable.DataEvent.TYPE_CHANGED
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream


/** WatchConnectivityPlugin */
class WatchConnectivityPlugin : FlutterPlugin, MethodCallHandler,
    MessageClient.OnMessageReceivedListener, DataClient.OnDataChangedListener {
    private val channelName = "watch_connectivity"

    /// The MethodChannel that will the communication between Flutter and native Android
    ///
    /// This local reference serves to register the plugin with the Flutter Engine and unregister it
    /// when the Flutter Engine is detached from the Activity
    private lateinit var channel: MethodChannel
    private lateinit var packageManager: PackageManager
    private lateinit var nodeClient: NodeClient
    private lateinit var messageClient: MessageClient
    private lateinit var dataClient: DataClient
    private lateinit var localNode: Node

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, channelName)
        channel.setMethodCallHandler(this)

        val context = flutterPluginBinding.applicationContext

        packageManager = context.packageManager
        nodeClient = Wearable.getNodeClient(context)
        messageClient = Wearable.getMessageClient(context)
        dataClient = Wearable.getDataClient(context)

        messageClient.addListener(this)
        dataClient.addListener(this)

        nodeClient.localNode.addOnSuccessListener { localNode = it }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        messageClient.removeListener(this)
        dataClient.removeListener(this)
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            // Getters
            "isSupported" -> result.success(true)
            "isPaired" -> isPaired(result)
            "isReachable" -> isReachable(result)
            "applicationContext" -> applicationContext(result)
            "receivedApplicationContexts" -> receivedApplicationContexts(result)

            // Methods
            "sendMessage" -> sendMessage(call, result)
            "updateApplicationContext" -> updateApplicationContext(call, result)

            // Not implemented
            else -> result.notImplemented()
        }
    }

    private fun objectToBytes(`object`: Any): ByteArray {
        val baos = ByteArrayOutputStream()
        val oos = ObjectOutputStream(baos)
        oos.writeObject(`object`)
        return baos.toByteArray()
    }

    private fun objectFromBytes(bytes: ByteArray): Any {
        val bis = ByteArrayInputStream(bytes)
        val ois = ObjectInputStream(bis)
        return ois.readObject()
    }

    private fun isPaired(result: Result) {
        log("calling isPaired")
        val apps = packageManager.getInstalledApplications(0)
        val wearableAppInstalled =
            apps.any { it.packageName == "com.google.android.wearable.app" || it.packageName == "com.samsung.android.app.watchmanager" }
        log("isPaired wearableAppInstalled = $wearableAppInstalled")
        nodeClient.connectedNodes.addOnSuccessListener {
            log("isPaired experiment: all connected nodes = $it")
        }
        result.success(wearableAppInstalled)
    }

    private fun isReachable(result: Result) {
        log("calling isReachable")
        nodeClient.connectedNodes
            .addOnSuccessListener {
                log("isReachable success $it")
                result.success(it.isNotEmpty())
            }
            .addOnFailureListener {
                log("isReachable failure $it")
                result.error(it.message ?: "", it.localizedMessage, it)
            }
    }

    @SuppressLint("VisibleForTests")
    private fun applicationContext(result: Result) {
        log("calling appContext")
        dataClient.dataItems
            .addOnSuccessListener { items ->
                log("calling appContext data items success = $items")
                val localNodeItem = items.firstOrNull {
                    log("calling appContext first or null $it")
                    // Only elements from the local node (there should only be one)
                    it.uri.host == localNode.id && it.uri.path == "/$channelName"
                }
                if (localNodeItem != null) {
                    log("calling appContext localNode not null")
                    val itemContent = objectFromBytes(localNodeItem.data!!)
                    result.success(itemContent)
                } else {
                    log("calling appContext localNode is null")
                    result.success(emptyMap<String, Any>())
                }
            }.addOnFailureListener {
                log("calling appContext failure $it")
                result.error(it.message ?: "", it.localizedMessage, it)
            }
    }

    @SuppressLint("VisibleForTests")
    private fun receivedApplicationContexts(result: Result) {
        log("calling receivedAppContext")
        dataClient.dataItems
            .addOnSuccessListener { items ->
                val itemContents = items.filter {
                    log("calling receivedAppContext filter items $it")
                    // Elements that are not from the local node
                    it.uri.host != localNode.id && it.uri.path == "/$channelName"
                }.map { objectFromBytes(it.data!!) }
                result.success(itemContents)
            }.addOnFailureListener {
                log("calling receivedAppContext failure $it")
                result.error(it.message ?: "", it.localizedMessage, it)
            }
    }

    private fun sendMessage(call: MethodCall, result: Result) {
        log("calling sendMessage")
        val messageData = objectToBytes(call.arguments)
        log("calling sendMessage data = $messageData")
        nodeClient.connectedNodes.addOnSuccessListener { nodes ->
            log("calling sendMessage success nodes = $nodes")
            nodes.forEach { messageClient.sendMessage(it.id, channelName, messageData) }
            result.success(null)
        }.addOnFailureListener {
            log("calling sendMessage failure = $it")
            result.error(it.message ?: "", it.localizedMessage, it)
        }
    }

    @SuppressLint("VisibleForTests")
    private fun updateApplicationContext(call: MethodCall, result: Result) {
        log("calling updateAppContext")
        val eventData = objectToBytes(call.arguments)
        val dataItem = PutDataRequest.create("/$channelName")
        dataItem.data = eventData
        dataClient.putDataItem(dataItem)
            .addOnSuccessListener {
                log("calling updateAppContext success")
                result.success(null)
            }
            .addOnFailureListener {
                log("calling updateAppContext failure")
                result.error(it.message ?: "", it.localizedMessage, it)
            }
    }

    override fun onMessageReceived(message: MessageEvent) {
        log("calling onMessageReceived")
        val messageContent = objectFromBytes(message.data)
        log("calling onMessageReceived content = $messageContent")
        channel.invokeMethod("didReceiveMessage", messageContent)
    }

    @SuppressLint("VisibleForTests")
    override fun onDataChanged(dataItems: DataEventBuffer) {
        log("calling onDataChanged")
        dataItems
            .filter {
                it.type == TYPE_CHANGED
                        && it.dataItem.uri.host != localNode.id
                        && it.dataItem.uri.path == "/$channelName"
            }
            .forEach { item ->
                log("calling onDataChanged for each $item")
                val eventContent = objectFromBytes(item.dataItem.data!!)
                log("calling onDataChanged content = $eventContent")
                channel.invokeMethod("didReceiveApplicationContext", eventContent)
            }
    }
    fun log(str: String) {
        channel.invokeMethod("log", mapOf("text" to str))
    }
}
