package ir.mrahimy.conceal.ui.home

import android.Manifest
import android.app.Activity
import android.content.Intent
import com.github.squti.androidwaverecorder.WaveRecorder
import ir.mrahimy.conceal.R
import ir.mrahimy.conceal.base.BaseActivity
import ir.mrahimy.conceal.databinding.ActivityMainBinding
import ir.mrahimy.conceal.enums.ChooserType
import ir.mrahimy.conceal.util.EventObsrver
import ir.mrahimy.conceal.util.WavUtil.fromWaveData
import ir.mrahimy.conceal.util.Wave
import kotlinx.android.synthetic.main.activity_main.*
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import permissions.dispatcher.NeedsPermission
import permissions.dispatcher.RuntimePermissions
import java.io.File
import java.util.*


const val PICK_IMAGE = 1000
const val PICK_AUDIO = 2000

@RuntimePermissions
class MainActivity : BaseActivity<MainActivityViewModel, ActivityMainBinding>() {

    private var isRecording = false

    private lateinit var waveRecorder: WaveRecorder
    private lateinit var filePath: String

    override val layoutRes = R.layout.activity_main
    override val viewModel: MainActivityViewModel by viewModel()

    private val adapter: RecordingsAdapter by inject()

    override fun bindObservables() {
        viewModel.onStartRecording.observe(this, EventObsrver {
            startRecordingWithPermissionCheck()
        })

        viewModel.onChooseImage.observe(this, EventObsrver {
            chooseMediaWithPermissionCheck(
                ChooserType.Image,
                getString(R.string.select_image_title),
                PICK_IMAGE
            )
        })

        viewModel.onChooseAudio.observe(this, EventObsrver {
            chooseMediaWithPermissionCheck(
                ChooserType.Audio,
                getString(R.string.select_audio_title),
                PICK_AUDIO
            )
        })
    }

    override fun initBinding() {
        binding.apply {
            lifecycleOwner = this@MainActivity
            vm = viewModel
            executePendingBindings()
        }
    }

    @NeedsPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
    fun chooseMedia(type: ChooserType, title: String, requestCode: Int) {
        val chooserIntent =
            createPickerIntent(type, title)
        startActivityForResult(chooserIntent, requestCode)
    }

    override fun configCreationEvents() {
        recordings_list?.adapter = adapter
    }

    override fun configResumeEvents() = Unit

    @NeedsPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.RECORD_AUDIO)
    fun startRecording() {
        val date = Date()

        if (isRecording) {
            waveRecorder.stopRecording()
            isRecording = false
            val waveFile = fromWaveData(Wave.WavFile.openWavFile(File(filePath)))
            return
        }

        filePath = externalCacheDir?.absolutePath + "/rec_${date.time}.wav"
        waveRecorder = WaveRecorder(filePath)
        waveRecorder.startRecording()
        isRecording = true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        onRequestPermissionsResult(requestCode, grantResults)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            PICK_IMAGE -> {
                if (resultCode == Activity.RESULT_CANCELED) return
                viewModel.selectImageFile(data)
            }

            PICK_AUDIO -> {
                if (resultCode == Activity.RESULT_CANCELED) return
                viewModel.selectAudioFile(data)
            }
        }
    }
}
