package com.mallow.cloudwatchlogger

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The class to maintain the shared preferences for cloud watch logger
 */
@Singleton
class DLLoggerPreferences @Inject constructor(@ApplicationContext context: Context) {
    private val preference: SharedPreferences =
        context.getSharedPreferences(BuildConfig.PREFERENCE, Context.MODE_PRIVATE)
    private val editor: SharedPreferences.Editor = preference.edit()

    companion object {
        private const val KEY_SEQUENCE_TOKEN = "sequence_token"
        private const val KEY_GROUP_NAME = "group_name"
        private const val KEY_STREAM_NAME = "stream_name"
        private const val KEY_APP_VERSION = "app_version"
    }

    /**
     * Property that contains the sequence token
     */
    var sequenceToken: String?
        get() = preference.getString(KEY_SEQUENCE_TOKEN, null)
        set(token) = editor.putString(KEY_SEQUENCE_TOKEN, token).apply()

    /**
     * Property that contains the log group name
     */
    var groupName: String?
        get() = preference.getString(KEY_GROUP_NAME, "default_log_group")
        set(name) = editor.putString(KEY_GROUP_NAME, name).apply()

    /**
     * Property that contains the log stream name
     */
    var streamName: String?
        get() = preference.getString(KEY_STREAM_NAME, "default_log_stream")
        set(name) = editor.putString(KEY_STREAM_NAME, name).apply()

    /**
     * Property that contains the app version
     */
    var appVersion: String?
        get() = preference.getString(KEY_APP_VERSION, null)
        set(appVersion) = editor.putString(KEY_APP_VERSION, appVersion).apply()

    /**
     * To clear the logger preferences
     */
    fun resetPreferences() {
        with(editor) {
            remove(KEY_SEQUENCE_TOKEN)
            remove(KEY_GROUP_NAME)
            remove(KEY_STREAM_NAME)
            remove(KEY_APP_VERSION)
            editor.apply()
        }
    }
}