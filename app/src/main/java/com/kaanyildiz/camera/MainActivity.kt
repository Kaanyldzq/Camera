package com.kaanyildiz.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.location.Location
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Environment
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var textureView: TextureView
    private lateinit var coordinatesTextView: TextView
    private lateinit var recordButton: Button
    private lateinit var switchCameraButton: Button
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    private lateinit var mediaRecorder: MediaRecorder
    private var isRecording = false
    private var videoFile: File? = null

    private var activeCameraLens = CameraCharacteristics.LENS_FACING_BACK

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        val locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true

        if (cameraGranted && audioGranted) openCamera(activeCameraLens)
        else Toast.makeText(this, "Kamera veya mikrofon izni verilmedi", Toast.LENGTH_SHORT).show()

        if (locationGranted) startLocationUpdates()
    }

    @SuppressLint("MissingPermission", "MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.textureViewBack)
        coordinatesTextView = findViewById(R.id.coordinatesTextView)
        recordButton = findViewById(R.id.recordButton)
        switchCameraButton = findViewById(R.id.switchCameraButton)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager

        // İzin kontrolü
        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            permissionsToRequest.add(Manifest.permission.CAMERA)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)

        if (permissionsToRequest.isNotEmpty()) requestPermissions.launch(permissionsToRequest.toTypedArray())
        else openCamera(activeCameraLens)

        startLocationUpdates()

        recordButton.setOnClickListener {
            if (!isRecording) startRecording() else stopRecording()
        }

        switchCameraButton.setOnClickListener {
            switchCamera()
        }
    }

    private fun openCamera(lensFacing: Int) {
        val cameraId = cameraManager.cameraIdList.first {
            cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == lensFacing
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice?.close()
                cameraDevice = camera
                startPreview()
            }

            override fun onDisconnected(camera: CameraDevice) { camera.close() }
            override fun onError(camera: CameraDevice, error: Int) { camera.close() }
        }, null)
    }

    private fun startPreview() {
        val camera = cameraDevice ?: return
        val texture = textureView.surfaceTexture ?: return
        texture.setDefaultBufferSize(1920, 1080)
        val previewSurface = Surface(texture)

        val surfaces = mutableListOf(previewSurface)
        if (isRecording) surfaces.add(mediaRecorder.surface)

        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        builder.addTarget(previewSurface)
        if (isRecording) builder.addTarget(mediaRecorder.surface)

        camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                captureSession?.setRepeatingRequest(builder.build(), null, null)
                if (isRecording) mediaRecorder.start()
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {}
        }, null)
    }

    private fun switchCamera() {
        activeCameraLens = if (activeCameraLens == CameraCharacteristics.LENS_FACING_BACK)
            CameraCharacteristics.LENS_FACING_FRONT
        else
            CameraCharacteristics.LENS_FACING_BACK

        if (isRecording) {
            // Kayıt sırasında kamera değişimi için MediaRecorder yeniden hazırlanır
            captureSession?.close()
            cameraDevice?.close()

            try {
                mediaRecorder.reset()
                val moviesDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                if (!moviesDir!!.exists()) moviesDir.mkdirs()
                videoFile = File(moviesDir, "video_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4")

                mediaRecorder.apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setOutputFile(videoFile!!.absolutePath)
                    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setVideoEncodingBitRate(5_000_000)
                    setVideoFrameRate(30)
                    setVideoSize(1920, 1080)

                    // Video yönü ayarı
                    val orientation = if (activeCameraLens == CameraCharacteristics.LENS_FACING_BACK) 90 else 270
                    setOrientationHint(orientation)

                    prepare()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            openCamera(activeCameraLens)
        } else {
            openCamera(activeCameraLens)
        }
    }

    private fun startRecording() {
        try {
            val moviesDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            if (!moviesDir!!.exists()) moviesDir.mkdirs()
            videoFile = File(
                moviesDir,
                "video_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
            )

            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(videoFile!!.absolutePath)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setVideoEncodingBitRate(5_000_000)
                setVideoFrameRate(30)
                setVideoSize(1920, 1080)

                // Video yönü ayarı
                val orientation = if (activeCameraLens == CameraCharacteristics.LENS_FACING_BACK) 90 else 270
                setOrientationHint(orientation)

                prepare()
            }

            isRecording = true
            recordButton.text = "Durdur"
            startPreview()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopRecording() {
        try {
            mediaRecorder.stop()
            mediaRecorder.reset()
            isRecording = false
            recordButton.text = "Kaydet"
            videoFile?.let {
                Toast.makeText(this, "Video kaydedildi: ${it.absolutePath}", Toast.LENGTH_SHORT).show()
            }
            startPreview()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            val latitude = location?.latitude ?: 0.0
            val longitude = location?.longitude ?: 0.0
            coordinatesTextView.text = "Lat: $latitude, Long: $longitude"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraDevice?.close()
    }
}
