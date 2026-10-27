package com.example.carriex.ui.chat

import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.Size

/** Keep the camera and its decoding frame upright for both pairing and contact QR codes. */
class PortraitQrCaptureActivity : CaptureActivity() {
    // CaptureActivity is not an AppCompatActivity; apply the same app locale on older Android too.
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.example.carriex.localization.AppLanguage.context(newBase))
    }

    override fun initializeContent(): DecoratedBarcodeView = super.initializeContent().also { scanner ->
        val side = minOf((280 * resources.displayMetrics.density).toInt(),
            resources.displayMetrics.widthPixels - (48 * resources.displayMetrics.density).toInt())
        scanner.barcodeView.setFramingRectSize(Size(side, side))
    }
}
