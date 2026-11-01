package com.mockgeo.app.status

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import com.mockgeo.core.model.ControlPlane

/**
 * 控制面状态回执 provider（模块 system_server 写入，本 App 读取）。
 *
 * 安全模型：
 * - insert/update：仅接受 system（uid 1000，即模块）与本进程自身；
 * - query：仅接受本进程自身（模拟坐标属于隐私数据，不暴露给第三方 App）；
 * - 其它 App 的任何调用返回空/拒绝。
 *
 * 单行 upsert：模块每次 insert 即覆盖（ContentResolver.insert 语义）。
 */
class StatusProvider : ContentProvider() {

    private val columns = arrayOf(
        ControlPlane.COL_ENABLED,
        ControlPlane.COL_REVISION,
        ControlPlane.COL_LAT,
        ControlPlane.COL_LNG,
        ControlPlane.COL_HOOKS,
        ControlPlane.COL_HOOK_DETAIL,
        ControlPlane.COL_LEASE_REMAINING_MS,
        ControlPlane.COL_LEASE_EXPIRATIONS,
        ControlPlane.COL_REWRITTEN_CALLBACKS,
        ControlPlane.COL_UNMATCHED_CALLBACKS,
        ControlPlane.COL_UPTIME_MS,
        ControlPlane.COL_UPDATED_AT,
        ControlPlane.COL_MODULE_VERSION,
        ControlPlane.COL_BACKEND,
        ControlPlane.COL_PROCESS_ID,
        ControlPlane.COL_INSTANCE_NONCE,
        ControlPlane.COL_RESPONSE_NONCE,
        ControlPlane.COL_LAST_CALLS,
        ControlPlane.COL_CURRENT_CALLS,
        ControlPlane.COL_LISTENER_CALLS,
        ControlPlane.COL_PUSHES,
        ControlPlane.COL_REPORT_MOCK,
    )

    @Volatile
    private var current: ContentValues? = null

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        if (!isSelf()) return emptyCursor()
        val data = current ?: return emptyCursor()
        val cursor = MatrixCursor(columns)
        cursor.addRow(
            columns.map { col ->
                when (col) {
                    ControlPlane.COL_ENABLED -> if (data.getAsBoolean(ControlPlane.COL_ENABLED) == true) 1 else 0
                    ControlPlane.COL_REVISION -> data.getAsLong(ControlPlane.COL_REVISION) ?: 0L
                    ControlPlane.COL_LAT -> data.getAsDouble(ControlPlane.COL_LAT) ?: 0.0
                    ControlPlane.COL_LNG -> data.getAsDouble(ControlPlane.COL_LNG) ?: 0.0
                    ControlPlane.COL_HOOKS -> if (data.getAsBoolean(ControlPlane.COL_HOOKS) == true) 1 else 0
                    ControlPlane.COL_HOOK_DETAIL -> data.getAsString(col) ?: ""
                    ControlPlane.COL_LEASE_REMAINING_MS, ControlPlane.COL_LEASE_EXPIRATIONS,
                    ControlPlane.COL_REWRITTEN_CALLBACKS, ControlPlane.COL_UNMATCHED_CALLBACKS -> data.getAsLong(col) ?: 0L
                    ControlPlane.COL_REPORT_MOCK -> if (data.getAsBoolean(ControlPlane.COL_REPORT_MOCK) != false) 1 else 0
                    ControlPlane.COL_PROCESS_ID -> data.getAsInteger(ControlPlane.COL_PROCESS_ID) ?: 0
                    ControlPlane.COL_BACKEND, ControlPlane.COL_INSTANCE_NONCE, ControlPlane.COL_RESPONSE_NONCE -> data.getAsString(col) ?: ""
                    ControlPlane.COL_UPTIME_MS -> data.getAsLong(ControlPlane.COL_UPTIME_MS) ?: 0L
                    ControlPlane.COL_UPDATED_AT -> data.getAsLong(ControlPlane.COL_UPDATED_AT) ?: 0L
                    ControlPlane.COL_LAST_CALLS, ControlPlane.COL_CURRENT_CALLS,
                    ControlPlane.COL_LISTENER_CALLS, ControlPlane.COL_PUSHES -> data.getAsLong(col) ?: 0L
                    else -> data.getAsString(ControlPlane.COL_MODULE_VERSION) ?: ""
                }
            },
        )
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (!isSystemOrSelf()) return null
        values ?: return null
        current = ContentValues(values)
        context?.contentResolver?.notifyChange(uri, null)
        return uri
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        if (!isSystemOrSelf()) return 0
        values ?: return 0
        current = ContentValues(values)
        context?.contentResolver?.notifyChange(uri, null)
        return 1
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.mockgeo.status"

    private fun isSystemOrSelf(): Boolean {
        val calling = Binder.getCallingUid()
        return calling == 1000 || calling == android.os.Process.myUid()
    }

    private fun isSelf(): Boolean = Binder.getCallingUid() == android.os.Process.myUid()

    private fun emptyCursor(): Cursor = MatrixCursor(columns)
}
