/*
 * Copyright 2011-2015 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.mycelium.spvmodule

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.*
import android.content.Context
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.net.ConnectivityManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.os.PowerManager.WakeLock
import android.support.v4.content.LocalBroadcastManager
import android.text.format.DateUtils
import android.util.Log
import com.mycelium.modularizationtools.CommunicationManager
import com.mycelium.spvmodule.BlockchainState.Impediment


import com.mycelium.spvmodule.providers.BlockchainContract
import org.bitcoinj.core.*
import org.bitcoinj.core.TransactionConfidence.ConfidenceType
import org.bitcoinj.core.listeners.AbstractPeerDataEventListener
import org.bitcoinj.core.listeners.PeerConnectedEventListener
import org.bitcoinj.core.listeners.PeerDisconnectedEventListener
import org.bitcoinj.net.discovery.MultiplexingDiscovery
import org.bitcoinj.net.discovery.PeerDiscovery
import org.bitcoinj.net.discovery.PeerDiscoveryException
import org.bitcoinj.store.BlockStore
import org.bitcoinj.store.BlockStoreException
import org.bitcoinj.store.SPVBlockStore
import org.bitcoinj.utils.Threading
import org.bitcoinj.wallet.Wallet
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class SpvService : Service() {
    private var application: SpvModuleApplication? = null
    private var config: Configuration? = null

    private var blockStore: BlockStore? = null
    private var blockChainFile: File? = null
    private var blockChain: BlockChain? = null
    private var peerGroup: PeerGroup? = null

    private val handler = Handler()
    private val delayHandler = Handler()
    private var wakeLock: WakeLock? = null

    private var peerConnectivityListener: PeerConnectivityListener? = null
    private var nm: NotificationManager? = null
    private val impediments = EnumSet.noneOf(Impediment::class.java)
    private val transactionsReceived = AtomicInteger()
    private var serviceCreatedAt: Long = 0
    private var resetBlockchainOnShutdown = false

    private val walletEventListener = object : ThrottlingWalletChangeListener(APPWIDGET_THROTTLE_MS) {
        override fun onThrottledWalletChanged() {
            // TODO: 8/11/16
            Log.d(this.javaClass.canonicalName, "onThrottledWalletChanged NOT doing anything as of now.")
        }

        override fun onTransactionConfidenceChanged(wallet: Wallet, tx: Transaction) {
            super.onTransactionConfidenceChanged(wallet, tx)
            if(BuildConfig.DEBUG) {
                Log.d(this.javaClass.canonicalName, "onTransactionConfidenceChanged, notifyTransaction, "
                        + "tx = " + tx.toString())
            }
            notifyTransaction(tx)
        }

        override fun onCoinsReceived(wallet: Wallet, tx: Transaction, prevBalance: Coin, newBalance: Coin) {
            transactionsReceived.incrementAndGet()

            val bestChainHeight = blockChain!!.bestChainHeight

            //val address = WalletUtils.getWalletAddressOfReceived(tx, wallet)
            val amount = tx.getValue(wallet)
            val confidenceType = tx.confidence.confidenceType

            handler.post {
                val isReceived = amount.signum() > 0
                val replaying = bestChainHeight < config!!.bestChainHeightEver
                val isReplayedTx = confidenceType == ConfidenceType.BUILDING && replaying
                if (isReceived && !isReplayedTx) {
                    if(BuildConfig.DEBUG) {
                        Log.d(this.javaClass.canonicalName, "onCoinsReceived, notifyTransaction, "
                                + "tx = " + tx.toString())
                    }
                    notifyTransaction(tx)
                }
            }
        }

        override fun onCoinsSent(wallet: Wallet, tx: Transaction, prevBalance: Coin, newBalance: Coin) {
            transactionsReceived.incrementAndGet()
            if(BuildConfig.DEBUG) {
                Log.d(this.javaClass.canonicalName, "onCoinsSent, notifyTransaction, "
                        + "tx = " + tx.toString())
            }
            notifyTransaction(tx)
        }
    }

    private fun notifyTransaction(tx: Transaction) {
        val txos = HashMap<String, TransactionOutput?>()
        tx.inputs.forEach {txos[it.outpoint.toString()] = it.connectedOutput}
        val contentResolver = contentResolver
        val values = ContentValues()
        val cursor = contentResolver.query(BlockchainContract.Transaction.CONTENT_URI(packageName),
                arrayOf(BlockchainContract.Transaction.TRANSACTION_ID),
                BlockchainContract.Transaction.TRANSACTION_ID + "=?", arrayOf(tx.hashAsString),
                null)
        val height:Int = if(tx.confidence == ConfidenceType.BUILDING) {
            tx.confidence.appearedAtChainHeight
        } else {
            -1
        }
        if (cursor == null || cursor.count == 0) {
            // insert
            values.put(BlockchainContract.Transaction.TRANSACTION_ID, tx.hashAsString)
            values.put(BlockchainContract.Transaction.TRANSACTION, tx.bitcoinSerialize())
            values.put(BlockchainContract.Transaction.INCLUDED_IN_BLOCK, height)
            contentResolver.insert(BlockchainContract.Transaction.CONTENT_URI(packageName), values)
            for(txo in tx.inputs) {
                val txoValues = ContentValues()
                txoValues.put(BlockchainContract.TransactionOutput.TXO_ID, txo.outpoint.toString())
                txoValues.put(BlockchainContract.TransactionOutput.TXO, txo.scriptBytes)
                try {
                    contentResolver.insert(BlockchainContract.TransactionOutput.CONTENT_URI(packageName), txoValues)
                } catch (e:RuntimeException) {
                    // HACK: Until we actually make use of ContentProvider and more specifically txos via CP, this will do.
                    Log.e(LOG_TAG, e.message)
                }
            }
        } else {
            // update
            values.put(BlockchainContract.Transaction.INCLUDED_IN_BLOCK, height)
            contentResolver.update(BlockchainContract.Transaction.CONTENT_URI(packageName), values, "_id=?",
                    arrayOf(tx.hashAsString))
        }
        cursor?.close()
        // send the new transaction and the *complete* utxo set of the wallet
        SpvMessageSender.sendTransactions(CommunicationManager.getInstance(this), setOf(tx), SpvModuleApplication.getWallet().unspents.toSet())
    }

    var peerCount: Int = 0
    private inner class PeerConnectivityListener internal constructor() : PeerConnectedEventListener, PeerDisconnectedEventListener, OnSharedPreferenceChangeListener {
        private val stopped = AtomicBoolean(false)

        init {
            config!!.registerOnSharedPreferenceChangeListener(this)
        }

        internal fun stop() {
            stopped.set(true)

            config!!.unregisterOnSharedPreferenceChangeListener(this)

            nm!!.cancel(Constants.NOTIFICATION_ID_CONNECTED)
        }

        override fun onPeerConnected(peer: Peer, peerCount: Int) = onPeerChanged(peerCount)

        override fun onPeerDisconnected(peer: Peer, peerCount: Int) = onPeerChanged(peerCount)

        private fun onPeerChanged(peerCount: Int) {
            this@SpvService.peerCount = peerCount
            changed()
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String) {
            if (Configuration.PREFS_KEY_CONNECTIVITY_NOTIFICATION == key) {
                changed()
            }
        }

        private fun changed() {
            if(!stopped.get()) {
                this@SpvService.changed()
            }
        }
    }

    private fun changed() {
        handler.post {
            val connectivityNotificationEnabled = config!!.connectivityNotificationEnabled

            if (!connectivityNotificationEnabled || peerCount == 0) {
                nm!!.cancel(Constants.NOTIFICATION_ID_CONNECTED)
            } else {
                val notification = Notification.Builder(this@SpvService)
                notification.setSmallIcon(R.drawable.stat_sys_peers, if (peerCount > 4) 4 else peerCount)
                notification.setContentTitle(getString(R.string.app_name))
                var contentText = getString(R.string.notification_peers_connected_msg, peerCount)
                val daysBehind = (Date().time - blockchainState.bestChainDate.time) / DateUtils.DAY_IN_MILLIS
                if(daysBehind > 1) {
                    contentText += " " +  getString(R.string.notification_chain_status_behind, daysBehind)
                }
                if(blockchainState.impediments.size > 0) {
                    // TODO: this is potentially unreachable as the service stops when offline.
                    // Not sure if impediment STORAGE ever shows. Probably both should show.
                    val impedimentsString = blockchainState.impediments.map {it.toString()}.joinToString()
                    contentText += " " +  getString(R.string.notification_chain_status_impediment, impedimentsString)
                }
                notification.setContentText(contentText)

                notification.setContentIntent(PendingIntent.getActivity(this@SpvService, 0, Intent(this@SpvService,
                        PreferenceActivity::class.java), 0))
                notification.setWhen(System.currentTimeMillis())
                notification.setOngoing(true)
                nm!!.notify(Constants.NOTIFICATION_ID_CONNECTED, notification.build())
            }

            // send broadcast
            broadcastPeerState(peerCount)
        }
    }

    private val blockchainDownloadListener = object : AbstractPeerDataEventListener() {
        private val lastMessageTime = AtomicLong(0)

        override fun onBlocksDownloaded(peer: Peer?, block: Block?, filteredBlock: FilteredBlock?, blocksLeft: Int) {
            delayHandler.removeCallbacksAndMessages(null)

            val now = System.currentTimeMillis()
            if (now - lastMessageTime.get() > BLOCKCHAIN_STATE_BROADCAST_THROTTLE_MS) {
                delayHandler.post(runnable)
            } else {
                delayHandler.postDelayed(runnable, BLOCKCHAIN_STATE_BROADCAST_THROTTLE_MS)
            }
        }

        private val runnable = Runnable {
            lastMessageTime.set(System.currentTimeMillis())

            config!!.maybeIncrementBestChainHeightEver(blockChain!!.chainHead.height)
            broadcastBlockchainState()
            changed()
        }
    }

    private val connectivityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action

            if (ConnectivityManager.CONNECTIVITY_ACTION == action) {
                val hasConnectivity = !intent.getBooleanExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, false)
                Log.i(LOG_TAG, "network is " + if (hasConnectivity) "up" else "down")

                if (hasConnectivity)
                    impediments.remove(Impediment.NETWORK)
                else
                    impediments.add(Impediment.NETWORK)
                check()
            } else if (Intent.ACTION_DEVICE_STORAGE_LOW == action) {
                Log.i(LOG_TAG, "device storage low")

                impediments.add(Impediment.STORAGE)
                check()
            } else if (Intent.ACTION_DEVICE_STORAGE_OK == action) {
                Log.i(LOG_TAG, "device storage ok")

                impediments.remove(Impediment.STORAGE)
                check()
            }
        }

        @SuppressLint("Wakelock")
        private fun check() {
            val wallet = SpvModuleApplication.getWallet()

            if (impediments.isEmpty() && peerGroup == null) {
                Log.d(LOG_TAG, "acquiring wakelock")
                wakeLock!!.acquire()

                // consistency check
                val walletLastBlockSeenHeight = wallet.lastBlockSeenHeight
                val bestChainHeight = blockChain!!.bestChainHeight
                if (walletLastBlockSeenHeight != -1 && walletLastBlockSeenHeight != bestChainHeight) {
                    val message = "wallet/blockchain out of sync: $walletLastBlockSeenHeight/$bestChainHeight"
                    Log.e(LOG_TAG, message)
                }

                Log.i(LOG_TAG, "starting peergroup")
                peerGroup = PeerGroup(Constants.NETWORK_PARAMETERS, blockChain)
                peerGroup!!.setDownloadTxDependencies(0) // recursive implementation causes StackOverflowError
                peerGroup!!.addWallet(wallet)
                peerGroup!!.setUserAgent(Constants.USER_AGENT, application!!.packageInfo!!.versionName)
                peerGroup!!.addConnectedEventListener(peerConnectivityListener)
                peerGroup!!.addDisconnectedEventListener(peerConnectivityListener)

                val maxConnectedPeers = application!!.maxConnectedPeers()

                val trustedPeerHost = config!!.trustedPeerHost
                val hasTrustedPeer = trustedPeerHost != null

                val connectTrustedPeerOnly = hasTrustedPeer && config!!.trustedPeerOnly
                peerGroup!!.maxConnections = if (connectTrustedPeerOnly) 1 else maxConnectedPeers
                peerGroup!!.setConnectTimeoutMillis(Constants.PEER_TIMEOUT_MS)
                peerGroup!!.setPeerDiscoveryTimeoutMillis(Constants.PEER_DISCOVERY_TIMEOUT_MS.toLong())

                peerGroup!!.addPeerDiscovery(object : PeerDiscovery {
                    private val normalPeerDiscovery = MultiplexingDiscovery.forServices(Constants.NETWORK_PARAMETERS, 0)

                    @Throws(PeerDiscoveryException::class)
                    override fun getPeers(services: Long, timeoutValue: Long, timeoutUnit: TimeUnit): Array<InetSocketAddress> {
                        val peers = LinkedList<InetSocketAddress>()

                        var needsTrimPeersWorkaround = false

                        if (hasTrustedPeer) {
                            Log.i(LOG_TAG, "trusted peer '$trustedPeerHost' ${if (connectTrustedPeerOnly) " only" else ""}")

                            val addr = InetSocketAddress(trustedPeerHost, Constants.NETWORK_PARAMETERS.port)
                            if (addr.address != null) {
                                peers.add(addr)
                                needsTrimPeersWorkaround = true
                            }
                        }

                        if (!connectTrustedPeerOnly)
                            peers.addAll(Arrays.asList(*normalPeerDiscovery.getPeers(services, timeoutValue, timeoutUnit)))

                        // workaround because PeerGroup will shuffle peers
                        if (needsTrimPeersWorkaround)
                            while (peers.size >= maxConnectedPeers)
                                peers.removeAt(peers.size - 1)

                        return peers.toTypedArray()
                    }

                    override fun shutdown() {
                        normalPeerDiscovery.shutdown()
                    }
                })

                // start peergroup
                peerGroup!!.startAsync()
                peerGroup!!.startBlockChainDownload(blockchainDownloadListener)
            } else if (!impediments.isEmpty() && peerGroup != null) {
                Log.i(LOG_TAG, "stopping peergroup")
                peerGroup!!.removeDisconnectedEventListener(peerConnectivityListener!!)
                peerGroup!!.removeConnectedEventListener(peerConnectivityListener!!)
                peerGroup!!.removeWallet(wallet)
                peerGroup!!.stopAsync()
                peerGroup = null

                Log.d(LOG_TAG, "releasing wakelock")
                wakeLock!!.release()
            }

            broadcastBlockchainState()
        }
    }

    private class ActivityHistoryEntry(val numTransactionsReceived: Int, val numBlocksDownloaded: Int) {
        override fun toString(): String {
            return "$numTransactionsReceived / $numBlocksDownloaded"
        }
    }

    private val tickReceiver = object : BroadcastReceiver() {
        private var lastChainHeight = 0
        private val activityHistory = LinkedList<ActivityHistoryEntry>()

        override fun onReceive(context: Context, intent: Intent) {
            val chainHeight = blockChain!!.bestChainHeight

            if (lastChainHeight > 0) {
                val numBlocksDownloaded = chainHeight - lastChainHeight
                val numTransactionsReceived = transactionsReceived.getAndSet(0)

                // push history
                activityHistory.add(0, ActivityHistoryEntry(numTransactionsReceived, numBlocksDownloaded))

                // trim
                while (activityHistory.size > MAX_HISTORY_SIZE)
                    activityHistory.removeAt(activityHistory.size - 1)

                // print
                val builder = StringBuilder()
                for (entry in activityHistory) {
                    if (builder.isNotEmpty()) {
                        builder.append(", ")
                    }
                    builder.append(entry)
                }
                Log.i(LOG_TAG, "History of transactions/blocks: " + builder)

                // determine if block and transaction activity is idling
                var isIdle = false
                if (activityHistory.size >= MIN_COLLECT_HISTORY) {
                    isIdle = true
                    for (i in activityHistory.indices) {
                        val entry = activityHistory[i]
                        val blocksActive = entry.numBlocksDownloaded > 0 && i <= IDLE_BLOCK_TIMEOUT_MIN
                        val transactionsActive = entry.numTransactionsReceived > 0 && i <= IDLE_TRANSACTION_TIMEOUT_MIN

                        if (blocksActive || transactionsActive) {
                            isIdle = false
                            break
                        }
                    }
                }

                // if idling, shutdown service
                if (isIdle) {
                    Log.i(LOG_TAG, "idling detected, stopping service")
                    stopSelf()
                }
            }

            lastChainHeight = chainHeight
        }
    }

    private val mBinder = object : Binder() {
        val service: SpvService
            get() = this@SpvService
    }

    override fun onBind(intent: Intent): IBinder? {
        // TODO: nope. we don't use this. at least not from other apps.
        return mBinder
    }

    override fun onUnbind(intent: Intent): Boolean {
        Log.d(LOG_TAG, ".onUnbind()")
        return super.onUnbind(intent)
    }

    override fun onCreate() {
        serviceCreatedAt = System.currentTimeMillis()
        Log.d(LOG_TAG, ".onCreate()")

        super.onCreate()

        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val lockName = "$packageName blockchain sync"

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, lockName)

        application = getApplication() as SpvModuleApplication
        config = application!!.configuration
        val wallet = SpvModuleApplication.getWallet()

        peerConnectivityListener = PeerConnectivityListener()

        broadcastPeerState(0)

        blockChainFile = File(getDir("blockstore", Context.MODE_PRIVATE), Constants.Files.BLOCKCHAIN_FILENAME)
        val blockChainFileExists = blockChainFile!!.exists()

        if (!blockChainFileExists) {
            Log.i(LOG_TAG, "blockchain does not exist, resetting wallet")
            wallet.reset()
        }

        try {
            blockStore = SPVBlockStore(Constants.NETWORK_PARAMETERS, blockChainFile!!)
            blockStore!!.chainHead // detect corruptions as early as possible

            val earliestKeyCreationTime = wallet.earliestKeyCreationTime

            if (!blockChainFileExists && earliestKeyCreationTime > 0) {
                try {
                    val start = System.currentTimeMillis()
                    val checkpointsInputStream = assets.open(Constants.Files.CHECKPOINTS_FILENAME)
                    CheckpointManager.checkpoint(Constants.NETWORK_PARAMETERS, checkpointsInputStream, blockStore!!, earliestKeyCreationTime)
                    Log.i(LOG_TAG, "checkpoints loaded from '${Constants.Files.CHECKPOINTS_FILENAME}', took ${System.currentTimeMillis() - start}ms")
                } catch (x: IOException) {
                    Log.e(LOG_TAG, "problem reading checkpoints, continuing without", x)
                }

            }
        } catch (x: BlockStoreException) {
            blockChainFile!!.delete()

            val msg = "blockstore cannot be created"
            Log.e(LOG_TAG, msg, x)
            throw Error(msg, x)
        }

        try {
            blockChain = BlockChain(Constants.NETWORK_PARAMETERS, wallet, blockStore!!)
        } catch (x: BlockStoreException) {
            throw Error("blockchain cannot be created", x)
        }

        val intentFilter = IntentFilter()
        intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        intentFilter.addAction(Intent.ACTION_DEVICE_STORAGE_LOW)
        intentFilter.addAction(Intent.ACTION_DEVICE_STORAGE_OK)
        registerReceiver(connectivityReceiver, intentFilter) // implicitly start PeerGroup
        wallet.addCoinsReceivedEventListener(Threading.SAME_THREAD, walletEventListener)
        wallet.addCoinsSentEventListener(Threading.SAME_THREAD, walletEventListener)
        wallet.addChangeEventListener(Threading.SAME_THREAD, walletEventListener)
        wallet.addTransactionConfidenceEventListener(Threading.SAME_THREAD, walletEventListener)

        registerReceiver(tickReceiver, IntentFilter(Intent.ACTION_TIME_TICK))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        org.bitcoinj.core.Context.propagate(Constants.CONTEXT)
        if (intent != null) {
            Log.i(LOG_TAG, "service start command: $intent ${
            if (intent.hasExtra(Intent.EXTRA_ALARM_COUNT))
                " (alarm count: ${intent.getIntExtra(Intent.EXTRA_ALARM_COUNT, 0)})"
            else
                ""
            }")

            when (intent.action) {
                ACTION_CANCEL_COINS_RECEIVED -> nm!!.cancel(Constants.NOTIFICATION_ID_COINS_RECEIVED)
                ACTION_RESET_BLOCKCHAIN -> {
                    Log.i(LOG_TAG, "will remove blockchain on service shutdown")

                    resetBlockchainOnShutdown = true
                    stopSelf()
                }
                ACTION_BROADCAST_TRANSACTION -> {
                    val transactionByteArray = intent.getByteArrayExtra("TX")
                    val tx = Transaction(Constants.NETWORK_PARAMETERS, transactionByteArray)
                    Log.i(LOG_TAG, "onReceive: TX = " + tx)
                    SpvModuleApplication.getWallet().maybeCommitTx(tx)
                    if (peerGroup != null) {
                        Log.i(LOG_TAG, "broadcasting transaction ${tx.hashAsString}")
                        peerGroup!!.broadcastTransaction(tx)
                    } else {
                        Log.w(LOG_TAG, "peergroup not available, not broadcasting transaction " + tx.hashAsString)
                    }
                }
            }
        } else {
            Log.w(LOG_TAG, "service restart, although it was started as non-sticky")
        }
        broadcastBlockchainState();
        return START_NOT_STICKY
    }

    private val LOG_TAG: String? = this::class.java.canonicalName

    override fun onDestroy() {
        Log.d(LOG_TAG, ".onDestroy()")

        SpvModuleApplication.scheduleStartBlockchainService(this)

        unregisterReceiver(tickReceiver)

        SpvModuleApplication.getWallet().removeChangeEventListener(walletEventListener)
        SpvModuleApplication.getWallet().removeCoinsSentEventListener(walletEventListener)
        SpvModuleApplication.getWallet().removeCoinsReceivedEventListener(walletEventListener)

        unregisterReceiver(connectivityReceiver)

        if (peerGroup != null) {
            peerGroup!!.removeDisconnectedEventListener(peerConnectivityListener!!)
            peerGroup!!.removeConnectedEventListener(peerConnectivityListener!!)
            peerGroup!!.removeWallet(SpvModuleApplication.getWallet())
            peerGroup!!.stop()

            Log.i(LOG_TAG, "peergroup stopped")
        }

        peerConnectivityListener!!.stop()

        delayHandler.removeCallbacksAndMessages(null)

        try {
            blockStore!!.close()
        } catch (x: BlockStoreException) {
            throw RuntimeException(x)
        }

        application!!.saveWallet()

        if (wakeLock!!.isHeld) {
            Log.d(LOG_TAG, "wakelock still held, releasing")
            wakeLock!!.release()
        }

        if (resetBlockchainOnShutdown) {
            Log.i(LOG_TAG, "removing blockchain")
            blockChainFile!!.delete()
        }

        super.onDestroy()

        Log.i(LOG_TAG, "service was up for ${(System.currentTimeMillis() - serviceCreatedAt) / 1000 / 60} minutes")
    }

    override fun onTrimMemory(level: Int) {
        Log.i(LOG_TAG, "onTrimMemory($level)")

        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            Log.w(LOG_TAG, "low memory detected, stopping service")
            stopSelf()
        }
    }

    val blockchainState: BlockchainState
        get() {
            val chainHead = blockChain!!.chainHead
            val bestChainDate = chainHead.header.time
            val bestChainHeight = chainHead.height
            val replaying = chainHead.height < config!!.bestChainHeightEver

            return BlockchainState(bestChainDate, bestChainHeight, replaying, impediments)
        }

    val connectedPeers: List<Peer>?
        get() {
            if (peerGroup != null)
                return peerGroup!!.connectedPeers
            else
                return null
        }

    fun getRecentBlocks(maxBlocks: Int): List<StoredBlock> {
        val blocks = ArrayList<StoredBlock>(maxBlocks)

        try {
            var block: StoredBlock? = blockChain!!.chainHead

            while (block != null) {
                blocks.add(block)

                if (blocks.size >= maxBlocks) {
                    break
                }

                block = block.getPrev(blockStore!!)
            }
        } catch (ignore: BlockStoreException) {
        }

        return blocks
    }

    private fun broadcastPeerState(numPeers: Int) {
        val broadcast = Intent(ACTION_PEER_STATE)
        broadcast.`package` = packageName
        broadcast.putExtra(ACTION_PEER_STATE_NUM_PEERS, numPeers)

        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast)
    }

    private fun broadcastBlockchainState() {
        val localBroadcast = Intent(ACTION_BLOCKCHAIN_STATE)
        localBroadcast.`package` = packageName
        blockchainState.putExtras(localBroadcast)
        LocalBroadcastManager.getInstance(this).sendBroadcast(localBroadcast)

        // "broadcast" to registered consumers
        val securedMulticastIntent = Intent()
        securedMulticastIntent.action = "com.mycelium.wallet.blockchainState"
        blockchainState.putExtras(securedMulticastIntent)
        SpvMessageSender.send(CommunicationManager.getInstance(this), securedMulticastIntent)
    }

    private fun send(receivingPackage: String, intent: Intent) {
        val communicationManager = CommunicationManager.getInstance(this)
        communicationManager.send(receivingPackage, intent)
    }

    companion object {
        private val PACKAGE_NAME = SpvService::class.java.`package`.name
        val ACTION_PEER_STATE = PACKAGE_NAME + ".peer_state"
        val ACTION_PEER_STATE_NUM_PEERS = "num_peers"
        val ACTION_BLOCKCHAIN_STATE = PACKAGE_NAME + ".blockchain_state"
        val ACTION_CANCEL_COINS_RECEIVED = PACKAGE_NAME + ".cancel_coins_received"
        val ACTION_RESET_BLOCKCHAIN = PACKAGE_NAME + ".reset_blockchain"
        val ACTION_BROADCAST_TRANSACTION = PACKAGE_NAME + ".broadcast_transaction"
        val ACTION_BROADCAST_TRANSACTION_HASH = "hash"

        private val MIN_COLLECT_HISTORY = 2
        private val IDLE_BLOCK_TIMEOUT_MIN = 2
        private val IDLE_TRANSACTION_TIMEOUT_MIN = 9
        private val MAX_HISTORY_SIZE = Math.max(IDLE_TRANSACTION_TIMEOUT_MIN, IDLE_BLOCK_TIMEOUT_MIN)
        private val APPWIDGET_THROTTLE_MS = DateUtils.SECOND_IN_MILLIS
        private val BLOCKCHAIN_STATE_BROADCAST_THROTTLE_MS = DateUtils.SECOND_IN_MILLIS
    }
}
