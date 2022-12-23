package com.mallow.cloudwatchlogger

import android.content.Context
import android.provider.Settings
import com.amazonaws.services.logs.AmazonCloudWatchLogsClient
import com.amazonaws.services.logs.model.*
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * The class that manages methods and members related to AWS cloudwatch logger
 */
class DLAwsLogger @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: DLLoggerPreferences,
    private val ioDispatcher: CoroutineDispatcher,
    private val applicationScope: CoroutineScope,
    private val gson: Gson,
    private val client: AmazonCloudWatchLogsClient,
) {
    companion object {
        private const val LOG_FILE_NAME = "DLCloudWatchLogs.txt"
        private const val TEMP_LOG_FILE_NAME = "DLCloudWatchLogsTemp.txt"
        private const val LOG_SEPARATOR = "|"
        private const val DATE_TIME_FORMAT = "yyyy-MM-dd HH:mm:ss"
        private const val BATCH_SIZE = 5000
        var totalLogEvents: MutableList<InputLogEvent> = mutableListOf()
    }

    /**
     * To cache the logs in the local storage as a text file
     *
     * @param message The log message
     */
    private fun cacheLogInLocalStorage(message: String?) {
        applicationScope.launch(ioDispatcher) {
            val file = File(context.filesDir, LOG_FILE_NAME)
            if (!file.exists()) {
                // Ignore the warning as we are already using IO Dispatcher
                file.createNewFile()
            }
            // Ignore the warning as we are already using IO Dispatcher
            // TODO: Need to check instantiating fileWriter for each log is correct
            FileWriter(file, true).use {
                it.appendLine(message)
            }
        }
    }

    /**
     * To get the complete log message
     *
     * @param logMessage The log message
     * @param label      The priority label
     * @return The complete log message
     */
    private fun getCompleteLogContent(
        logMessage: String,
        label: String?,
        phase: String?,
        module: String?
    ): String? {
        val logMessageBuilder = StringBuilder()
        with(logMessageBuilder) {
            label?.let { append(it).append(LOG_SEPARATOR) }
            append(getDeviceDetails()).append(LOG_SEPARATOR)
            phase?.let { append(it).append(LOG_SEPARATOR) }
            module?.let { append(it).append(LOG_SEPARATOR) }
            append(getCurrentDate()).append(LOG_SEPARATOR)
            append(logMessage)
            val completeMessage = InputLogEvent().apply {
                message = this@with.toString()
                timestamp = System.currentTimeMillis()
            }
            return gson.toJson(completeMessage)
        }
    }

    /**
     * To make log
     *
     * @param logMessage The log message
     * @param label      The priority label
     * @param phase      The phase of the project
     * @param module     The module of the project
     */
    fun log(
        logMessage: String,
        label: String? = null,
        phase: String? = null,
        module: String? = null
    ) = cacheLogInLocalStorage(getCompleteLogContent(logMessage, label, phase, module))

    /**
     * To set the log group name
     *
     * @param name The log group name
     */
    fun setLogGroupName(name: String) {
        preferences.groupName = name
    }

    /**
     * To set the current app version
     *
     * @param appVersion The app version
     */
    fun setAppVersion(appVersion: String) {
        preferences.appVersion = appVersion
    }

    /**
     * To clear the logger preferences
     */
    fun resetLoggerPreferences() = preferences.resetPreferences()

    /**
     * To create the log stream
     *
     * @param logStreamName The log stream name
     */
    fun createLogStream(logStreamName: String) {
        preferences.streamName = logStreamName
        with(CreateLogStreamRequest()) {
            logGroupName = preferences.groupName
            this.logStreamName = logStreamName
            client.createLogStream(this)
        }
    }

    /**
     * To upload the logs to the aws cloud
     */
    suspend fun uploadLogs() {
        withContext(ioDispatcher) {
            val bufferedReader = File(context.filesDir, LOG_FILE_NAME).bufferedReader()
            // Here we are converting the json into corresponding object and appending it a list of log events
            val events = bufferedReader
                .readLines()
                .map { gson.fromJson(it, InputLogEvent::class.java) } as MutableList<InputLogEvent>
            totalLogEvents.addAll(events)
            splitIntoBatchesAndUpload(events)
        }
    }

    /**
     * To order the logs with the latest timestamp in chronological order and upload log
     */
    private fun changeTimestampAndUploadLogs() {
        val currentTimeStamp = System.currentTimeMillis()
        totalLogEvents.forEach { it.timestamp = currentTimeStamp }
        splitIntoBatchesAndUpload(totalLogEvents)
    }

    /**
     * To split all the logs into batches based on certain time and count constraints
     *
     * @param events  The log events
     * @param batches The resulting batches
     */
    private fun splitIntoBatchesAndUpload(
        events: MutableList<InputLogEvent>,
        batches: MutableList<List<InputLogEvent>> = mutableListOf()
    ) {
        if (events.isEmpty()) return
        val startTimeOfBatch = events.first().timestamp
        val endTimeOfBatch = startTimeOfBatch.plus(TimeUnit.DAYS.toMillis(1))
        val batchesBasedOnTime = events.groupBy {
            it.timestamp <= endTimeOfBatch
        }
        batchesBasedOnTime[true]?.let { _logEvents ->
            _logEvents.chunked(BATCH_SIZE).forEach {
                batches.add(it)
            }
        }
        batchesBasedOnTime[true]?.let { events.removeAll(it) }
        if (events.isNotEmpty()) splitIntoBatchesAndUpload(events, batches)
        uploadBatchesToAws(batches)
    }

    /**
     * To delete the uploaded logs
     *
     * @param uploadedLogs The uploaded logs
     */
    private fun deleteUploadedLogs(uploadedLogs: List<InputLogEvent>) {
        applicationScope.launch(ioDispatcher) {
            totalLogEvents.removeAll(uploadedLogs)
            //Create temp file to store logs that are not uploaded
            val file = File(context.filesDir, TEMP_LOG_FILE_NAME)
            if (!file.exists()) {
                // Ignore the warning as we are already using IO Dispatcher
                file.createNewFile()
            }
            // Here we are using buffered writer since the number of line is large, if lines is less we could
            // have used fileWriter
            file.bufferedWriter().use { writer ->
                totalLogEvents.forEach {
                    writer.appendLine(gson.toJson(it))
                }
            }
            //Delete the log file which include all logs including the uploaded ones
            File(context.filesDir, LOG_FILE_NAME).delete()
            //Rename the temp file that includes only logs that are not uploaded with the deleted log file name
            file.renameTo(File(context.filesDir, LOG_FILE_NAME))
        }
    }

    /**
     * To upload log batches to aws
     *
     * @param batches The log batches
     */
    private fun uploadBatchesToAws(batches: MutableList<List<InputLogEvent>>) {
        for (batch in batches) {
            try {
                uploadLogsToAws(batch)
            } catch (exception: Exception) {
                when (exception) {
                    // This exception occurs when we have given a wrong sequence token in the putLogEvents request. This exception returns a expected
                    // sequence token that needs to be sent in the next putLogEvents request. So here we are retrying with the expected sequence token.
                    is InvalidSequenceTokenException -> {
                        preferences.sequenceToken = exception.expectedSequenceToken
                        uploadLogsToAws(batch)
                    }
                    // This exception occurs when we try to upload the same log event twice. So here we are removing the logs that are
                    // already uploaded and saving the expected sequence token for next putLogEvents request.
                    is DataAlreadyAcceptedException -> {
                        preferences.sequenceToken = exception.expectedSequenceToken
                        deleteUploadedLogs(batch)
                    }
                    // This exception occurs when we try to upload logs without creating log group or log stream or with wrong name for each, so if this exception occur
                    // we will retry creating log stream, so this function can succeed when called next time
                    is ResourceNotFoundException -> {
                        preferences.streamName?.let { createLogStream(it) }
                        return
                    }
                    // This exception occurs if we order the logs with timestamp with wrong order, so we will update all the logs with the
                    // same timestamp and upload it again
                    is InvalidParameterException -> changeTimestampAndUploadLogs()
                    else -> throw exception
                }
            }
        }
    }

    /**
     * To upload logs to aws
     *
     * @param logs The logs
     */
    private fun uploadLogsToAws(logs: List<InputLogEvent>) {
        val request = PutLogEventsRequest()
        request.apply {
            setLogEvents(logs)
            logGroupName = preferences.groupName
            logStreamName = preferences.streamName
            val token = preferences.sequenceToken
            if (token != null) sequenceToken = token
        }
        val result = client.putLogEvents(request)
        //The successful result contains the next sequence token which need to be sent in the next putLogEvents request
        result?.nextSequenceToken?.let { preferences.sequenceToken = it }
        //Here we are deleting the log file as it was uploaded
        deleteUploadedLogs(logs)
    }

    /**
     * To get the total logs count
     *
     * @return The total logs count
     */
    suspend fun getSavedLogsCount(): Int = withContext(ioDispatcher) {
        var count = 0
        val bufferedReader = File(context.filesDir, LOG_FILE_NAME).bufferedReader()
        bufferedReader.forEachLine { count++ }
        count
    }

    /**
     * To get the device basic details
     *
     * @return The concatenated device details
     */
    private fun getDeviceDetails(): String {
        val deviceModel = android.os.Build.MODEL ?: ""
        val osVersion = android.os.Build.VERSION.SDK_INT
        val manufacturer = android.os.Build.MANUFACTURER ?: ""
        val deviceId =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        val appVersion = preferences.appVersion ?: ""
        return deviceModel + LOG_SEPARATOR + manufacturer + LOG_SEPARATOR + osVersion + LOG_SEPARATOR + deviceId + LOG_SEPARATOR + appVersion
    }

    /**
     * To get the current data and time
     *
     * @return Current locale date
     */
    private fun getCurrentDate(): String {
        val currentDateFormat = SimpleDateFormat(DATE_TIME_FORMAT, Locale.getDefault())
        return currentDateFormat.format(Date())
    }
}