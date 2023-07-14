package com.anago.frida

import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.anago.frida.databinding.ActivityMainBinding
import com.anago.frida.network.GithubAPI.getFridaVersions
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {
    init {
        Shell.enableVerboseLogging = true
    }

    private lateinit var mBinding: ActivityMainBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(mBinding.root)

        mBinding.downloadBtn.setOnClickListener {
            showVersionsDialog()
        }

        mBinding.startBtn.setOnClickListener {
            showDownloadedVersionsDialog(true)
        }

        mBinding.stopBtn.setOnClickListener {
            showDownloadedVersionsDialog(false)
        }

        mBinding.stopAllBtn.setOnClickListener {
            stopAllFridaServers()
        }
    }

    private fun showDownloadedVersionsDialog(startServer: Boolean) {
        val downloadedVersions: MutableList<Pair<String, String>> = mutableListOf()
        val dir = File(filesDir, "frida")
        dir.list()?.forEach {
            val regex = "frida-(\\d+\\.\\d+\\.\\d+)-(.*$)".toRegex()
            val result = regex.find(it)
            val version = result?.groupValues?.get(1) ?: return@forEach
            val abi = result.groupValues[2]
            downloadedVersions.add(Pair(version, abi))
        }
        val items = downloadedVersions.map {
            "${it.first} | ${it.second}"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Downloaded Frida Servers")
            .setItems(items) { _, which ->
                val selectedVersion = downloadedVersions[which]
                if (startServer) {
                    startFridaServer(selectedVersion.first, selectedVersion.second)
                } else {
                    stopFridaServer(selectedVersion.first, selectedVersion.second)
                }
            }.show()
    }

    private fun startFridaServer(version: String, abi: String) {
        val fileName = "frida-${version}-$abi"
        val filePath = File(filesDir, "frida/$fileName")
        if (!filePath.exists()) {
            return
        }
        Shell.cmd(
            "su",
            "chmod 777 ${filePath.absolutePath}",
            "${filePath.absolutePath} &"
        ).exec()
    }

    private fun stopFridaServer(version: String, abi: String) {
        killPid(version, abi)
    }

    private fun stopAllFridaServers() {
        val fridaDir = File(filesDir, "frida")
        fridaDir.listFiles()?.forEach {
            killPid(it.absolutePath)
        }
    }

    private fun killPid(version: String, abi: String) {
        val fileName = "frida-${version}-$abi"
        val filePath = File(filesDir, "frida/$fileName")
        if (!filePath.exists()) {
            return
        }
        killPid(filePath.absolutePath)
    }

    private fun killPid(filePath: String) {
        val pids = getPids(filePath)
        pids.forEach { pid ->
            if (pid != -1) {
                Shell.cmd(
                    "su",
                    "kill $pid"
                ).exec()
            }
        }
    }

    private fun getPids(version: String, abi: String): List<Int> {
        val fileName = "frida-${version}-$abi"
        val filePath = File(filesDir, "frida/$fileName")
        if (!filePath.exists()) {
            return emptyList()
        }
        return getPids(filePath.absolutePath)
    }

    private fun getPids(filePath: String): List<Int> {
        val result = Shell.cmd(
            "su",
            "pgrep -f $filePath"
        ).exec()
        return result.out.map {
            it.toIntOrNull() ?: -1
        }
    }

    private fun showVersionsDialog() {
        getFridaVersions { versions: List<String> ->
            val items = versions.toTypedArray()
            MaterialAlertDialogBuilder(this).setItems(items) { _, which ->
                val selectedVersion = items[which]
                showSelectAbisDialog(selectedVersion)
            }.show()
        }
    }

    private fun showSelectAbisDialog(version: String) {
        val abis = arrayOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        MaterialAlertDialogBuilder(this).setTitle("Frida v$version").setItems(abis) { _, which ->
            val selectedAbi = abis[which]
            downloadFridaServer(version, selectedAbi)
        }.show()
    }

    private fun downloadFridaServer(version: String, abi: String) {
        val downloadUrl = when (abi) {
            "arm64-v8a" -> "https://github.com/frida/frida/releases/download/$version/frida-server-$version-android-arm64.xz"
            "armeabi-v7a" -> "https://github.com/frida/frida/releases/download/$version/frida-server-$version-android-arm.xz"
            "x86" -> "https://github.com/frida/frida/releases/download/$version/frida-server-$version-android-x86.xz"
            "x86_64" -> "https://github.com/frida/frida/releases/download/$version/frida-server-$version-android-x86_64.xz"
            else -> return
        }

        val progressBar = LinearProgressIndicator(this).apply {
            max = 100
        }
        val snackBar = Snackbar.make(
            mBinding.root, "Downloading Frida Server...", Snackbar.LENGTH_INDEFINITE
        ).apply {
            val snackBarLayout = view as Snackbar.SnackbarLayout
            snackBarLayout.addView(progressBar)
        }
        snackBar.show()

        CoroutineScope(Dispatchers.IO).launch {
            val dest = File(filesDir, "frida/frida-${version}-${abi}")
            if (!dest.parentFile!!.exists()) {
                dest.parentFile!!.mkdir()
            }

            val url = URL(downloadUrl)
            val httpURLConnection = url.openConnection() as HttpURLConnection
            httpURLConnection.requestMethod = "GET"
            httpURLConnection.connect()

            val fileLength = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                httpURLConnection.contentLengthLong
            } else {
                httpURLConnection.contentLength.toLong()
            }
            val inputStream = try {
                httpURLConnection.inputStream
            } catch (e: FileNotFoundException) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "not found", Toast.LENGTH_SHORT).show()
                    snackBar.dismiss()
                    httpURLConnection.disconnect()
                }
                return@launch
            }
            val outputStream = dest.outputStream()

            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var totalBytes = 0L
            var bytes: Int

            while (inputStream.read(buffer).also { bytes = it } != -1) {
                outputStream.write(buffer, 0, bytes)
                totalBytes += bytes
                val progress = (totalBytes * 100) / fileLength
                withContext(Dispatchers.Main) {
                    progressBar.progress = progress.toInt()
                }
            }

            inputStream.close()
            outputStream.close()
            httpURLConnection.disconnect()

            withContext(Dispatchers.Main) {
                snackBar.setText("Downloaded.")
                Toast.makeText(this@MainActivity, "Downloaded.", Toast.LENGTH_SHORT).show()
            }
            delay(2000)
            withContext(Dispatchers.Main) {
                snackBar.dismiss()
            }
        }
    }
}