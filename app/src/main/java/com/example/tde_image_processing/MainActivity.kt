package com.example.tde_image_processing

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.tde_image_processing.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private lateinit var capturedImageView: ImageView

    private lateinit var captureButton: Button
    private lateinit var saveButton: Button
    private lateinit var cancelButton: Button

    private lateinit var filterButtonLayout: LinearLayout
    private lateinit var grayScaleButton: Button
    private lateinit var filtersButton: Button
    private lateinit var brightnessContrastButton: Button
    private lateinit var edgeDetectionButton: Button

    private lateinit var brightnessContrastLayout: RelativeLayout
    private lateinit var brightnessSeekBar: SeekBar
    private lateinit var contrastSeekBar: SeekBar

    private lateinit var cameraProvider: ProcessCameraProvider
    private lateinit var currentImageBitmap: Bitmap
    private var editedImageBitmap: Bitmap? = null

    private var edgeDetection = false

    private val TAG = "camerax"
    private val FILE_NAME_FORMAT = "yy-MM-dd-HH-mm-ss-SSS"
    private val REQUEST_CODE_PERMISSIONS = 123
    private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        outputDirectory = getOutputDirectory()
        cameraExecutor = Executors.newSingleThreadExecutor()
        capturedImageView = binding.capturedImageView

        captureButton = binding.captureButton
        saveButton = binding.saveButton
        cancelButton = binding.cancelButton

        filtersButton = binding.filtersButton
        grayScaleButton = binding.grayScaleButton
        brightnessContrastButton = binding.brightnessContrastButton
        edgeDetectionButton = binding.edgeDetectionButton
        filterButtonLayout = binding.filterButtonsLayout

        brightnessContrastLayout = binding.brightnessContrastLayout
        brightnessSeekBar = binding.brightnessSeekBar
        contrastSeekBar = binding.contrastSeekBar

        if (allPermissionsGranted()) {
            Toast.makeText(this, "Permissão concedida", Toast.LENGTH_SHORT).show()
            initializeCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS
            )
        }

        captureButton.setOnClickListener {
            takePhoto()
        }

        saveButton.setOnClickListener {
            savePhoto()
        }

        cancelButton.setOnClickListener {
            cancelCapture()
        }

        brightnessContrastButton.setOnClickListener {
            if (brightnessContrastLayout.visibility == View.VISIBLE) {
                brightnessContrastLayout.visibility = View.GONE
            } else {
                brightnessContrastLayout.visibility = View.VISIBLE
            }
            brightnessSeekBar.progress = 50
            contrastSeekBar.progress = 50
            edgeDetection = false

            //Gabriel Barros adicionar seus layouts aqui para desabilitar eles
        }

        edgeDetectionButton.setOnClickListener {
            brightnessContrastLayout.visibility = View.GONE

            if (!edgeDetection) {
                edgeDetection = true
                sobelEdgeDetection(currentImageBitmap).also {
                    capturedImageView.setImageBitmap(it)
                }
            } else {
                edgeDetection = false
                capturedImageView.setImageBitmap(currentImageBitmap)
            }

            //Gabriel Barros adicionar seus layouts aqui para desabilitar eles
        }

        brightnessSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val updatedBitmap = updateBrightnessAndContrast(currentImageBitmap, progress, contrastSeekBar.progress)
                capturedImageView.setImageBitmap(updatedBitmap)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
            }
        })

        contrastSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val updatedBitmap = updateBrightnessAndContrast(currentImageBitmap, brightnessSeekBar.progress, progress)
                capturedImageView.setImageBitmap(updatedBitmap)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
            }
        })
    }

    private fun updateBrightnessAndContrast(bitmap: Bitmap, brightness: Int, contrast: Int): Bitmap {
        val adjustedBrightness = (brightness - 100) * 2.0f
        val adjustedContrast = (contrast - 50) * 2.0f

        val brightnessMatrix = ColorMatrix().apply {
            set(floatArrayOf(
                1f, 0f, 0f, 0f, adjustedBrightness,
                0f, 1f, 0f, 0f, adjustedBrightness,
                0f, 0f, 1f, 0f, adjustedBrightness,
                0f, 0f, 0f, 1f, 0f
            ))
        }

        val contrastMatrix = ColorMatrix().apply {
            val scale = adjustedContrast / 100f + 1f
            val translate = (-.5f * adjustedContrast / 100f + .5f) * 255f
            set(floatArrayOf(
                scale, 0f, 0f, 0f, translate,
                0f, scale, 0f, 0f, translate,
                0f, 0f, scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            ))
        }

        val combinedMatrix = ColorMatrix().apply {
            preConcat(brightnessMatrix)
            preConcat(contrastMatrix)
        }

        return applyColorMatrix(bitmap, combinedMatrix)
    }

    fun sobelEdgeDetection(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val edgeBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val sobelX = arrayOf(
            intArrayOf(-1, 0, 1),
            intArrayOf(-2, 0, 2),
            intArrayOf(-1, 0, 1)
        )

        val sobelY = arrayOf(
            intArrayOf(-1, -2, -1),
            intArrayOf(0, 0, 0),
            intArrayOf(1, 2, 1)
        )

        for (x in 1 until width - 1) {
            for (y in 1 until height - 1) {
                var sumX = 0
                var sumY = 0

                for (i in -1..1) {
                    for (j in -1..1) {
                        val pixel = bitmap.getPixel(x + i, y + j)
                        val gray = Color.red(pixel)
                        sumX += gray * sobelX[i + 1][j + 1]
                        sumY += gray * sobelY[i + 1][j + 1]
                    }
                }

                val magnitude = Math.sqrt((sumX * sumX + sumY * sumY).toDouble()).toInt()
                val newColor = if (magnitude > 128) Color.WHITE else Color.BLACK
                edgeBitmap.setPixel(x, y, newColor)
            }
        }

        editedImageBitmap = edgeBitmap
        return edgeBitmap
    }

    private fun applyColorMatrix(bitmap: Bitmap, colorMatrix: ColorMatrix): Bitmap {
        val outputBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config)
        val canvas = android.graphics.Canvas(outputBitmap)
        val paint = android.graphics.Paint().apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix)
        }
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        editedImageBitmap = outputBitmap
        return outputBitmap
    }

    private fun allPermissionsGranted() =
        REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(
                baseContext, it
            ) == PackageManager.PERMISSION_GRANTED
        }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                initializeCamera()
            } else {
                Toast.makeText(this, "Permissões de câmera negadas", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun getOutputDirectory(): File {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        } else {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        }
    }

    private fun initializeCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also { mPreview ->
                mPreview.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder().build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Erro ao conectar a câmera", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun cancelCapture() {
        edgeDetection = false
        capturedImageView.visibility = View.GONE
        saveButton.isEnabled = false
        filterButtonLayout.visibility = View.GONE
        cancelButton.isEnabled = false
        brightnessContrastLayout.visibility = View.GONE
        initializeCamera()
    }

    private fun takePhoto() {
        try {
            val imageCapture = imageCapture ?: return

            imageCapture.takePicture(
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.capacity())
                        buffer.get(bytes)

                        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

                        if (image.imageInfo.rotationDegrees != 0) {
                            val matrix = Matrix()
                            matrix.postRotate(image.imageInfo.rotationDegrees.toFloat())
                            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                        }

                        capturedImageView.post {
                            capturedImageView.setImageBitmap(bitmap)
                            capturedImageView.visibility = View.VISIBLE
                        }

                        image.close()
                        currentImageBitmap = bitmap
                        cameraProvider.unbindAll()

                        saveButton.isEnabled = true
                        filterButtonLayout.visibility = View.VISIBLE
                        cancelButton.isEnabled = true
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e(TAG, "Erro ao capturar imagem: ${exception.message}", exception)
                    }
                })
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao capturar imagem: ${e.message}", e)
        }
    }

    private fun savePhoto() {
        try {
            val currentImageBitmap = editedImageBitmap ?: currentImageBitmap ?: return

            val photoFile = File(
                outputDirectory,
                SimpleDateFormat(FILE_NAME_FORMAT, Locale.getDefault()).format(System.currentTimeMillis()) + ".jpg"
            )

            photoFile.outputStream().use { outputStream ->
                currentImageBitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
            }

            Toast.makeText(this, "Imagem salva na galeria.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("TAG", "Erro ao salvar imagem: ${e.message}", e)
        }
    }




    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
