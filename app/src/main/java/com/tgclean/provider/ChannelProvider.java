package com.tgclean.provider;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 频道发现 ContentProvider — 接收 Hook 端自动采集的频道数据
 *
 * URI: content://com.tgclean.provider.channels/discovered
 *
 * Hook 端（Telegram 进程）通过 ContentResolver.insert() 写入频道信息：
 *   values = { "dialog_id": -1001234567, "name": "频道名称" }
 *
 * App 端通过 ContentResolver.query() 读取所有已发现的频道。
 *
 * 数据存储在模块自己的 SQLite 数据库中（/data/data/com.tgclean/databases/）。
 */
public class ChannelProvider extends ContentProvider {

    private static final String TAG = "TGClean-ChannelProvider";

    public static final String AUTHORITY = "com.tgclean.provider.channels";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/discovered");

    // MIME types
    private static final String MIME_DIR = "vnd.android.cursor.dir/vnd.tgclean.channel";
    private static final String MIME_ITEM = "vnd.android.cursor.item/vnd.tgclean.channel";

    // 列名
    public static final String COL_ID = "_id";
    public static final String COL_DIALOG_ID = "dialog_id";
    public static final String COL_NAME = "name";
    public static final String COL_LAST_SEEN = "last_seen";

    private static final int CODE_DISCOVERED = 1;
    private static final int CODE_DISCOVERED_ITEM = 2;

    private static final UriMatcher uriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
    static {
        uriMatcher.addURI(AUTHORITY, "discovered", CODE_DISCOVERED);
        uriMatcher.addURI(AUTHORITY, "discovered/#", CODE_DISCOVERED_ITEM);
    }

    private ChannelDbHelper dbHelper;

    @Override
    public boolean onCreate() {
        dbHelper = new ChannelDbHelper(getContext());
        return true;
    }

    @Override
    public Uri insert(@NonNull Uri uri, ContentValues values) {
        if (uriMatcher.match(uri) != CODE_DISCOVERED) {
            throw new IllegalArgumentException("Unknown URI: " + uri);
        }

        SQLiteDatabase db = dbHelper.getWritableDatabase();
        long dialogId = values.getAsLong(COL_DIALOG_ID);

        // UPSERT: 按 dialog_id 去重，有则更新 last_seen，无则插入
        long rowId = db.insertWithOnConflict(
                ChannelDbHelper.TABLE_NAME, null, values,
                SQLiteDatabase.CONFLICT_REPLACE);

        if (rowId > 0) {
            Uri resultUri = ContentUris.withAppendedId(CONTENT_URI, rowId);
            getContext().getContentResolver().notifyChange(resultUri, null);
            Log.i(TAG, "Inserted/updated channel: dialogId=" + dialogId
                    + ", name=" + values.getAsString(COL_NAME));
            return resultUri;
        }
        return null;
    }

    @Override
    public Cursor query(@NonNull Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        // 默认按最后见到的时间倒序
        if (sortOrder == null) {
            sortOrder = COL_LAST_SEEN + " DESC";
        }

        Cursor cursor = db.query(
                ChannelDbHelper.TABLE_NAME, projection, selection, selectionArgs,
                null, null, sortOrder);
        cursor.setNotificationUri(getContext().getContentResolver(), CONTENT_URI);
        return cursor;
    }

    @Override
    public int delete(@NonNull Uri uri, String selection, String[] selectionArgs) {
        if (uriMatcher.match(uri) == CODE_DISCOVERED_ITEM) {
            String id = uri.getLastPathSegment();
            return dbHelper.getWritableDatabase().delete(
                    ChannelDbHelper.TABLE_NAME, COL_ID + "=?", new String[]{id});
        }
        return dbHelper.getWritableDatabase().delete(
                ChannelDbHelper.TABLE_NAME, selection, selectionArgs);
    }

    @Override
    public int update(@NonNull Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        if (uriMatcher.match(uri) == CODE_DISCOVERED_ITEM) {
            String id = uri.getLastPathSegment();
            return dbHelper.getWritableDatabase().update(
                    ChannelDbHelper.TABLE_NAME, values, COL_ID + "=?", new String[]{id});
        }
        return dbHelper.getWritableDatabase().update(
                ChannelDbHelper.TABLE_NAME, values, selection, selectionArgs);
    }

    @Override
    public String getType(@NonNull Uri uri) {
        switch (uriMatcher.match(uri)) {
            case CODE_DISCOVERED: return MIME_DIR;
            case CODE_DISCOVERED_ITEM: return MIME_ITEM;
            default: throw new IllegalArgumentException("Unknown URI: " + uri);
        }
    }

    // ═════════════════════════════════════════════
    // SQLite Helper
    // ═════════════════════════════════════════════

    static class ChannelDbHelper extends SQLiteOpenHelper {
        static final String TABLE_NAME = "discovered_channels";
        private static final String DB_NAME = "tgclean_channels.db";
        private static final int DB_VERSION = 1;

        ChannelDbHelper(Context context) {
            super(context, DB_NAME, null, DB_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + TABLE_NAME + " ("
                    + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + COL_DIALOG_ID + " INTEGER UNIQUE NOT NULL,"
                    + COL_NAME + " TEXT,"
                    + COL_LAST_SEEN + " INTEGER NOT NULL"
                    + ")");
            db.execSQL("CREATE INDEX idx_dialog_id ON " + TABLE_NAME + "(" + COL_DIALOG_ID + ")");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_NAME);
            onCreate(db);
        }
    }
}
