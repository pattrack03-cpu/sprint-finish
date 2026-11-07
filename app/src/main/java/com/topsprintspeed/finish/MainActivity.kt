package com.topsprintspeed.finish

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.topsprintspeed.finish.ui.AppRoot
import com.topsprintspeed.finish.ui.SprintTheme

class MainActivity : ComponentActivity() {
    private lateinit var model: AppModel

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        model.hasCameraPermission = hasCamera()
        if (model.hasCameraPermission) model.openCamera()
    }

    private fun hasCamera() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        model = AppModel(this)
        model.hasCameraPermission = hasCamera()
        val need = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        val missing = need.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
        setContent { SprintTheme { AppRoot(model) } }
    }

    override fun onResume() {
        super.onResume()
        model.hasCameraPermission = hasCamera()
        model.openCamera()
    }

    override fun onPause() {
        super.onPause()
        model.closeCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        model.release()
    }
}
