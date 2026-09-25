package com.example.carriex.ui.chat

import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.Size

/** Keep the camera and its decoding frame upright for both pairing and contact QR codes. */
class PortraitQrCaptureActivity : CaptureActivity() {
    override fun initializeContent(): DecoratedBarcodeView = super.initializeContent().also { scanner ->
        val side = minOf((280 * resources.displayMetrics.density).toInt(),
            resources.displayMetrics.widthPixels - (48 * resources.displayMetrics.density).toInt())
        scanner.barcodeView.setFramingRectSize(Size(side, side))
    }
}
