package ir.mrahimy.conceal.ui.home

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.map
import androidx.lifecycle.viewModelScope
import com.github.squti.androidwaverecorder.WaveRecorder
import ir.mrahimy.conceal.R
import ir.mrahimy.conceal.base.BaseAndroidViewModel
import ir.mrahimy.conceal.data.*
import ir.mrahimy.conceal.data.capsules.*
import ir.mrahimy.conceal.util.*
import ir.mrahimy.conceal.util.ktx.getNameFromPath
import ir.mrahimy.conceal.util.ktx.getPathJava
import ir.mrahimy.conceal.util.ktx.getRgbArray
import ir.mrahimy.conceal.util.ktx.parseWaver
import kotlinx.coroutines.*
import java.io.File
import java.util.*

class MainActivityViewModel(
    application: Application,
    private val model: MainActivityModel
) : BaseAndroidViewModel(application, model) {

    private lateinit var waveRecorder: WaveRecorder

    private val _onAddingMaxAmplitude = MutableLiveData<Event<Int>>()
    val onAddingMaxAmplitude: LiveData<Event<Int>>
        get() = _onAddingMaxAmplitude

    private val _recordingFilePath = MutableLiveData<String>()

    private val _isRecording = MutableLiveData<Boolean>(false)
    val isRecording: LiveData<Boolean>
        get() = _isRecording

    val recordBottomMargin = _isRecording.map {
        if (it) getDimension(R.dimen.record_bottom_margin_active)
        else getDimension(R.dimen.record_bottom_margin_passive)
    }

    private val waveFileSavingState = MutableLiveData<FileSavingState>(FileSavingState.IDLE)

    val recordings = model.getAllRecordings().map { list -> list.map { it.fill() } }
    val isRecordingListEmpty = recordings.map { it.isNullOrEmpty() }

    private val _onStartRecording = MutableLiveData<StatelessEvent>()
    val onStartRecording: LiveData<StatelessEvent>
        get() = _onStartRecording

    private val inputImagePath = MutableLiveData<String>(null)
    private val _inputImage = MutableLiveData<Bitmap>(null)
    val inputImage: LiveData<Bitmap>
        get() = _inputImage

    val isInputHintVisible = _inputImage.map { it == null }

    private val inputWavePath = MutableLiveData<String>(null)
    private val _inputWave = inputWavePath.map {
        if (it == null) null else File(it)
    }

    private val _recordTooltip = MutableLiveData<Int>(null)
    val recordTooltip: LiveData<Int>
        get() = _recordTooltip

    private val _inputImageSelectionTooltip = MutableLiveData<Int>(null)
    val inputImageSelectionTooltip: LiveData<Int>
        get() = _inputImageSelectionTooltip

    private val _inputWaveSelectionTooltip = MutableLiveData<Int>(null)
    val inputWaveSelectionTooltip: LiveData<Int>
        get() = _inputWaveSelectionTooltip

    private val _outputImageLabel = MutableLiveData<String>(getString(R.string.choose_input_image))
    val outputImageLabel: LiveData<String>
        get() = _outputImageLabel

    private val _waveFileLabel =
        MutableLiveData<String>(getString(R.string.click_to_open_file))
    val waveFileLabel: LiveData<String>
        get() = _waveFileLabel

    private val _snackMessage = MutableLiveData<Event<Int>>()
    val snackMessage: LiveData<Event<Int>>
        get() = _snackMessage

    private val _inputError = MutableLiveData<String>(getString(R.string.empty))
    val outputHintTextColor = _inputError.map {
        /**
         * could not find the best color for error
         */
        if (it.isNullOrBlank()) getColor(R.color.text_color)
        else getColor(R.color.text_color)
    }

    private val _waveInfo = _inputWave.map {
        if (it == null) return@map null
        try {
            WavUtil.fromWaveData(
                Wave.WavFile.openWavFile(it)
            ).apply { maxValue = data.maxValue() }
        } catch (e: Wave.WavFileException) {
            e.printStackTrace()
            val errorStringRes = e.code.mapToErrorStringRes()
            _inputError.postValue(getString(errorStringRes))
            viewModelScope.launch {
                delay(20)
                cancelConcealJob()
            }
            null
        }
    }

    private val _isDataExceeding = MutableLiveData<Boolean>(false)
    val isOutputHintVisible =
        combine(_inputImage, _inputWave, _waveInfo, _inputError, _isDataExceeding)
        { inputImage, inputWave, waveInfo, inputError, isDataExceeding ->
            if (inputImage == null) {
                _outputImageLabel.postValue(getString(R.string.choose_input_image))
                return@combine true
            }

            if (inputWave == null) {
                _outputImageLabel.postValue(getString(R.string.choose_wave_file_label))
                return@combine true
            }

            if (waveInfo == null || isDataExceeding == true) {
                _outputImageLabel.postValue(inputError)
                return@combine true
            }

            return@combine false
        } as MutableLiveData

    val handle = combine(_inputImage, _waveInfo) { _image, _waveFile ->
        val image = _image ?: return@combine null
        val waveFile = _waveFile ?: return@combine null
        putWaveFileIntoImage(image, waveFile)
        return@combine 1
    }

    private val _isInputImageLoading = MutableLiveData<Boolean>(false)
    val isInputImageLoading: LiveData<Boolean>
        get() = _isInputImageLoading

    private val _isInputWaveLoading = MutableLiveData<Boolean>(false)
    val isInputWaveLoading: LiveData<Boolean>
        get() = _isInputWaveLoading

    private val _concealPercentage = MutableLiveData<ConcealPercentage>(empty())
    val concealPercentage: LiveData<ConcealPercentage>
        get() = _concealPercentage

    val percentInt = _concealPercentage.map { it.percent.toInt() }
    val isOutputImageVisible = combine(_concealPercentage, isOutputHintVisible) { per, hint ->
        per?.data != null || hint == false
    }

    val isPercentageVisible =
        combine(
            isOutputHintVisible,
            _concealPercentage,
            waveFileSavingState
        ) { outputHint, percentageData, waveFileSavingState ->
            (outputHint == false && percentageData?.done == false) || waveFileSavingState == FileSavingState.SAVING
        } as MutableLiveData

    val isDoneMarkVisible =
        combine(
            isOutputHintVisible,
            _concealPercentage,
            waveFileSavingState
        ) { outputHint, percentageData, waveFileSavingState ->
            outputHint == false && percentageData?.done == true && waveFileSavingState == FileSavingState.DONE
        }

    val isSavingFileTextVisible =
        combine(
            isOutputHintVisible,
            _concealPercentage,
            waveFileSavingState
        ) { outputHint, percentageData, waveFileSavingState ->
            outputHint == false && percentageData?.done == true && waveFileSavingState == FileSavingState.SAVING
        }

    private val _onStartRgbListPutAll = MutableLiveData<Event<ConcealInputData>>()
    val onStartRgbListPutAll: LiveData<Event<ConcealInputData>>
        get() = _onStartRgbListPutAll

    private lateinit var concealJob: Job
    private lateinit var saveFileJob: Job

    fun cancelConcealJob() {
        if (::concealJob.isInitialized)
            concealJob.cancel()
        if (::saveFileJob.isInitialized)
            saveFileJob.cancel()

        viewModelScope.launch {
            delay(20)
            _concealPercentage.postValue(empty())
            delay(20)
            waveFileSavingState.postValue(FileSavingState.IDLE)
            delay(20)
            isPercentageVisible.postValue(false)
        }
    }

    private val _onDataExceeds = MutableLiveData<StatelessEvent>()
    val onDataExceeds: LiveData<StatelessEvent>
        get() = _onDataExceeds

    private fun putWaveFileIntoImage(
        image: Bitmap,
        waveFile: Waver
    ) = viewModelScope.launch {
        val rgbList = image.getRgbArray().remove3Lsb()
        val audioDataAsRgbList = waveFile.data.mapToUniformDouble().mapToRgbValue()
        try {
            concealJob = Job()
            _onStartRgbListPutAll.postValue(
                Event(
                    ConcealInputData(
                        rgbList,
                        rgbList.putWaverHeaderInfo(waveFile),
                        audioDataAsRgbList,
                        image,
                        concealJob
                    )
                )
            )
        } catch (e: IndexOutOfBoundsException) {
            e.printStackTrace()
            tellDataExceeds(e)
        }
    }

    private fun tellDataExceeds(e: Exception) {
        val stringRes =
            if (e is HugeFileException) getString(R.string.data_exceeds_on_index, e.index)
            else getString(R.string.data_exceeds)

        _inputError.postValue(stringRes)
        _isDataExceeding.postValue(true)
        viewModelScope.launch {
            delay(20)
            cancelConcealJob()
        }
    }

    init {
        viewModelScope.launch {
            delay(1000)
            _inputImageSelectionTooltip.postValue(R.string.select_image_tooltip)
        }
    }

    private val _onStopPlaying = MutableLiveData<StatelessEvent>()
    val onStopPlaying: LiveData<StatelessEvent>
        get() = _onStopPlaying

    /**
     * calls an event to get permission and then the view calls [startRecordingWave]
     */
    fun startRecording() {
        _recordTooltip.postValue(null)

        _concealPercentage.value?.apply {
            if (!done && percent > 0f) {
                _snackMessage.postValue(Event(R.string.please_cancel_first))
                return
            }
        }

        _mediaState.value?.let {
            if (it == MediaState.PLAY) {
                _onStopPlaying.postValue(StatelessEvent())
            }
        }

        _onStartRecording.postValue(StatelessEvent())
    }

    fun startRecordingWave() {
        val date = Date()
        val isRecording = _isRecording.value ?: false
        if (isRecording) {
            waveRecorder.stopRecording()
            _isRecording.postValue(false)
            val filePath = _recordingFilePath.value ?: return
            selectAudioFile(filePath)
            return
        }

        val filePath =
            getApplication().applicationContext.externalCacheDir?.absolutePath +
                    "/rec_${date.time}.wav"
        _recordingFilePath.postValue(filePath)
        waveRecorder = WaveRecorder(filePath)
        waveRecorder.startRecording()
        waveRecorder.onAmplitudeListener = {
            Log.d("onAmplitudeListener", it.toString())
            if (it != 0) _onAddingMaxAmplitude.postValue(Event(it))
        }
        _isRecording.postValue(true)
    }

    fun updatePercentage(concealPercentage: ConcealPercentage) {
        _concealPercentage.postValue(concealPercentage)
        if (concealPercentage.done) {
            concealPercentage.data?.let { outputBitmap ->
                saveFileJob = Job()
                viewModelScope.launch(saveFileJob + Dispatchers.Default) {
                    waveFileSavingState.postValue(FileSavingState.SAVING)
                    getApplication().applicationContext.externalCacheDir?.absolutePath?.let {

                        val inputImagePath = inputImagePath.value ?: return@launch
                        val inputWavePath = inputWavePath.value ?: return@launch
                        val imageName = inputImagePath.getNameFromPath()
                        val bitmapInfo = SaveBitmapInfoCapsule(
                            "${imageName}_conceal",
                            Date(),
                            outputBitmap,
                            Bitmap.CompressFormat.PNG
                        )
                        val outputImagePath = withContext(saveFileJob + Dispatchers.IO) {
                            bitmapInfo.save(it)
                        }

                        val waver = withContext(saveFileJob + Dispatchers.IO) {
                            outputBitmap.parseWaver()
                        }

                        val wavInfo = SaveWaveInfoCapsule("parsed_from_$imageName", Date(), waver)
                        val parsedWavePath = withContext(saveFileJob + Dispatchers.IO) {
                            try {
                                wavInfo.save(it)
                            } catch (e: ArrayIndexOutOfBoundsException) {
                                e.printStackTrace()
                                tellDataExceeds(e)
                                null
                            }
                        }

                        viewModelScope.launch {
                            model.addRecording(
                                Recording(
                                    0L,
                                    inputImagePath,
                                    outputImagePath,
                                    inputWavePath,
                                    parsedWavePath,
                                    Date().time
                                )
                            )
                        }
                    }
                    waveFileSavingState.postValue(FileSavingState.DONE)
                }
            }
        }
    }

    fun showSlide(position: Int) {
        val input = _inputImage.value ?: return
//        val output = outputBitmap.value ?: return
//        val bitmapArray = arrayOf(input, output)
        //TODO: navigate to slide show activity with bitmapArray & index position
    }

    private val _onChooseImage = MutableLiveData<StatelessEvent>()
    val onChooseImage: LiveData<StatelessEvent>
        get() = _onChooseImage

    fun chooseImage() {
        _concealPercentage.value?.apply {
            if (!done && percent > 0f) {
                _snackMessage.postValue(Event(R.string.please_cancel_first))
                return
            }
        }
        _onChooseImage.postValue(StatelessEvent())
    }

    private val _onChooseAudio = MutableLiveData<StatelessEvent>()
    val onChooseAudio: LiveData<StatelessEvent>
        get() = _onChooseAudio

    fun chooseAudio() {
        _concealPercentage.value?.apply {
            if (!done && percent > 0f) {
                _snackMessage.postValue(Event(R.string.please_cancel_first))
                return
            }
        }
        _onChooseAudio.postValue(StatelessEvent())
    }

    fun selectImageFile(data: Intent?) {
        _isDataExceeding.postValue(false)
        viewModelScope.launch {
            _isInputImageLoading.postValue(true)
            delay(10)
            data?.data?.let {
                delay(10)
                val file = it.getPathJava(getApplication().applicationContext)
                delay(10)
                inputImagePath.postValue(file)
                _inputImage.postValue(BitmapFactory.decodeFile(file))
                _isInputImageLoading.postValue(false)
                _concealPercentage.postValue(empty())
            }
            if (_inputWave.value == null) {
                _inputWaveSelectionTooltip.postValue(R.string.select_audio_file_tooltip)
                if (_isRecording.value == false) {
                    _recordTooltip.postValue(R.string.hold_to_start_recording_tooltip)
                }
            }
        }
    }

    fun selectAudioFile(data: Intent?) {
        _isDataExceeding.postValue(false)
        viewModelScope.launch {
            _isInputWaveLoading.postValue(true)
            delay(20)
            selectAudioFile(data?.data?.getPathJava(getApplication().applicationContext))
        }
    }

    private fun selectAudioFile(path: String?) {
        viewModelScope.launch {
            _isInputWaveLoading.postValue(true)
            delay(20)
            path?.let {
                _isInputWaveLoading.postValue(false)
                _waveFileLabel.postValue(it)
                delay(20)
                inputWavePath.postValue(it)
            }
        }
    }

    fun onUpdateInserting(result: LocalResult<ConcealPercentage>) {
        when (result) {
            is LocalResult.Success -> updatePercentage(result.data)
            is LocalResult.Error -> tellDataExceeds(result.e)
        }
    }

    fun delete(rec: Recording) = viewModelScope.launch {
        model.deleteRecording(rec)
    }

    private val _mediaState = MutableLiveData<MediaState>(MediaState.STOP)

    val recordingDrawable = combine(_isRecording, _mediaState) { isRecording, mediaState ->
        if (isRecording == true || mediaState != MediaState.PLAY) R.drawable.mic
        else R.drawable.ic_stop_fill
    }

    fun onMediaStateChanged(mediaState: MediaState) {
        _mediaState.postValue(mediaState)
    }
}

enum class FileSavingState {
    SAVING, IDLE, DONE
}