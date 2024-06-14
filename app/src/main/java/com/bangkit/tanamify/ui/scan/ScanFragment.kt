package com.bangkit.tanamify.ui.scan

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity.RESULT_OK
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.bangkit.tanamify.R
import com.bangkit.tanamify.databinding.FragmentScanBinding
import com.bangkit.tanamify.helper.ImageClassifierHelper
import com.bangkit.tanamify.ui.home.HomeViewModel
import com.bangkit.tanamify.ui.result.ResultActivity
import com.bangkit.tanamify.utils.ViewModelFactory
import com.yalantis.ucrop.UCrop
import org.tensorflow.lite.task.vision.classifier.Classifications
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class ScanFragment : Fragment() {
    private var _binding: FragmentScanBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HomeViewModel by viewModels {
        ViewModelFactory.getInstance(requireActivity().application)
    }

    private var currentImageUri: Uri? = null
    private var photoURI: Uri? = null
    private lateinit var currentPhotoPath: String

    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                showToast("Permission request granted")
            } else {
                showToast("Permission request denied")
            }
        }

    private fun allPermissionsGranted() =
        ContextCompat.checkSelfPermission(
            requireContext(),
            REQUIRED_PERMISSION
        ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentScanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (!allPermissionsGranted()) {
            requestPermissionLauncher.launch(REQUIRED_PERMISSION)
        }

        binding.apply {
            btnAnalyze.setOnClickListener {
                currentImageUri?.let {
                    analyzeImage(it)
                } ?: showToast("No image selected")
            }
            btnGallery.setOnClickListener {
                startGallery()
            }
            btnCamera.setOnClickListener {
                startCamera()
            }
        }
    }

    private fun startGallery() {
        launcherGallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private val launcherGallery = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            UCrop.of(
                uri,
                Uri.fromFile(requireContext().cacheDir.resolve("${System.currentTimeMillis()}.jpg"))
            )
                .withAspectRatio(16F, 9F)
                .withMaxResultSize(2000, 2000)
                .start(requireContext(), this)
        } else {
            Log.d("Photo Picker", "No media selected")
        }
    }

    @SuppressLint("QueryPermissionsNeeded")
    private fun startCamera() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (intent.resolveActivity(requireActivity().packageManager) != null) {
            val photoFile: File? = try {
                createImageFile()
            } catch (ex: IOException) {
                showToast("Error occurred while creating the file")
                null
            }
            photoFile?.also {
                photoURI = FileProvider.getUriForFile(
                    requireContext(),
                    "com.bangkit.tanamify.fileprovider",
                    it
                )
                intent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI)
                startActivityForResult(intent, REQUEST_IMAGE_CAPTURE)
            }
        }
    }

    @Throws(IOException::class)
    private fun createImageFile(): File {
        val timeStamp: String =
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir: File = requireContext().getExternalFilesDir(null)!!
        return File.createTempFile(
            "JPEG_${timeStamp}_",
            ".jpg",
            storageDir
        ).apply {
            currentPhotoPath = absolutePath
        }
    }

    @Deprecated(
        "Deprecated in Java", ReplaceWith(
            "super.onActivityResult(requestCode, resultCode, data)",
            "androidx.fragment.app.Fragment"
        )
    )
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            when (requestCode) {
                UCrop.REQUEST_CROP -> {
                    val resultUri = UCrop.getOutput(data!!)
                    currentImageUri = resultUri
                    showImage()
                }

                REQUEST_IMAGE_CAPTURE -> {
                    currentImageUri = photoURI
                    currentImageUri?.let {
                        UCrop.of(it, Uri.fromFile(File(currentPhotoPath)))
                            .withAspectRatio(16F, 9F)
                            .withMaxResultSize(2000, 2000)
                            .start(requireContext(), this)
                    }
                }
            }
        } else if (resultCode == UCrop.RESULT_ERROR) {
            val cropError = UCrop.getError(data!!)
            Log.e("Crop Error", "onActivityResult: $cropError")
        }
    }

    private fun showImage() {
        currentImageUri?.let {
            binding.imageView.setImageURI(it)
            binding.btnAnalyze.visibility = View.VISIBLE
            binding.btnGallery.apply {
                text = resources.getString(R.string.replace_image)
                setTextColor(ContextCompat.getColor(requireContext(), R.color.green_primary))
                background = ContextCompat.getDrawable(requireContext(), R.drawable.button_outline)
            }
        }
    }

    private fun analyzeImage(image: Uri) {
        val imageHelper = ImageClassifierHelper(
            context = requireContext(),
            classifierListener = object : ImageClassifierHelper.ClassifierListener {
                override fun onError(error: String) {
                    showToast(error)
                }

                override fun onResults(results: List<Classifications>?) {
                    results?.let {
                        val soilType = it[0].categories[0].label
                        handleClassificationResults(soilType)
                    } ?: showToast("No results from image classification")
                }
            }
        )
        imageHelper.classifyStaticImage(image)
    }

    private fun handleClassificationResults(soilType: String) {
        val temperature = binding.temperatureInput.text.toString().toFloatOrNull() ?: 0.0f
        val humidity = binding.humidityInput.text.toString().toFloatOrNull() ?: 0.0f
        val rainfall = binding.rainfallInput.text.toString().toFloatOrNull() ?: 0.0f
        val sunlight = binding.sunlightInput.text.toString().toFloatOrNull() ?: 0.0f

        val soilTypeNumeric = when (soilType) {
            "01-Aluvial" -> 1.0f
            "02-Andosol" -> 2.0f
            "03-Entisol" -> 3.0f
            "04-Humus" -> 4.0f
            "05-Inceptisol" -> 5.0f
            "06-Laterit" -> 6.0f
            "07-Kapur" -> 7.0f
            "08-Pasir" -> 8.0f
            else -> 0.0f
        }

        val inputArray = floatArrayOf(temperature, humidity, rainfall, sunlight, soilTypeNumeric)
        moveToResult(currentImageUri ?: error("Current image URI is null"), soilType, inputArray)
    }

    private fun moveToResult(imageUri: Uri, soilClassification: String, inputArray: FloatArray) {
        val intent = Intent(requireContext(), ResultActivity::class.java).apply {
            putExtra(ResultActivity.EXTRA_IMAGE_URI, imageUri.toString())
            putExtra(ResultActivity.EXTRA_SOIL_CLASSIFICATION, soilClassification)
            putExtra(ResultActivity.EXTRA_INPUT_ARRAY, inputArray)
        }
        startActivity(intent)
    }


    private fun showToast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val REQUIRED_PERMISSION = Manifest.permission.READ_EXTERNAL_STORAGE
        private const val REQUEST_IMAGE_CAPTURE = 1
    }
}
